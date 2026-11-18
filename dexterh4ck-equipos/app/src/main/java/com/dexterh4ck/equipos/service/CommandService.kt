package com.dexterh4ck.equipos.service

import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.dexterh4ck.equipos.data.*
import com.dexterh4ck.equipos.util.DevicePolicyHelper
import com.dexterh4ck.equipos.util.PairingCode
import com.dexterh4ck.equipos.util.KioskManager
import com.dexterh4ck.equipos.util.NotifyHelper
import com.dexterh4ck.equipos.util.UsageStatsHelper
import com.dexterh4ck.equipos.util.ParentalControlStore
import org.json.JSONObject
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en segundo plano de Dexterh4ck Equipos.
 *
 * Hace polling al backend cada 5 segundos buscando tareas pendientes
 * (install, uninstall, launch, list_apps, device_info, reboot, pin_app),
 * las ejecuta en el dispositivo y reporta el resultado.
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "DexterEquipos"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "dexter_equipos_channel"
        private const val POLL_INTERVAL = 5_000L
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val registrationInProgress = AtomicBoolean(false)
    @Volatile private var lastRegistrationAt = 0L
    private lateinit var config: AgentConfig
    private lateinit var api: KioskApi
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val outgoing = chain.request()
            val token = AgentTokenStore.getToken(this@CommandService)
            val backendHost = config.serverUrl.toHttpUrlOrNull()?.host
            val request = outgoing.newBuilder().apply {
                // Never leak the per-device bearer token to APK/CDN URLs or any other host.
                if (
                    !token.isNullOrBlank() &&
                    outgoing.url.isHttps &&
                    backendHost != null &&
                    outgoing.url.host.equals(backendHost, ignoreCase = true)
                ) {
                    header("Authorization", "Bearer $token")
                } else {
                    removeHeader("Authorization")
                }
            }.build()
            chain.proceed(request)
        }
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        config = loadConfig()
        setupNotificationChannel()
        api = createApiClient(config.serverUrl)
        registerDevice()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Dexterh4ck Equipos activo"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ====================== CONFIGURACIÓN ======================

    private fun loadConfig(): AgentConfig {
        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        return AgentConfig(
            deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, androidId) ?: androidId,
            deviceName = prefs.getString(AgentConfig.KEY_DEVICE_NAME, "Equipo TV") ?: "Equipo TV",
            isPaired = prefs.getBoolean(AgentConfig.KEY_IS_PAIRED, false),
            serverUrl = (prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL)
                ?: AgentConfig.DEFAULT_SERVER_URL).let { saved ->
                if (saved.contains("supabase.co", ignoreCase = true)) AgentConfig.DEFAULT_SERVER_URL else saved
            }
        )
    }

    private fun createApiClient(serverUrl: String): KioskApi {
        val baseUrl = if (serverUrl.isNotEmpty()) {
            serverUrl.trimEnd('/') + "/"
        } else {
            AgentConfig.DEFAULT_SERVER_URL + "/"
        }
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(KioskApi::class.java)
    }

    // ====================== REGISTRO DEL EQUIPO ======================

    private fun registerDevice() {
        if (!registrationInProgress.compareAndSet(false, true)) return
        lastRegistrationAt = System.currentTimeMillis()
        scope.launch {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())
                val device = DeviceRegistration(
                    device_id = config.deviceId,
                    hardware_id = config.deviceId,
                    pairing_code = PairingCode.getOrCreate(this@CommandService),
                    name = config.deviceName,
                    type = "android_tv",
                    platform = "fireos".takeIf { Build.MANUFACTURER.contains("Amazon", ignoreCase = true) } ?: "android",
                    app_version = "1.0.0",
                    status = "online",
                    last_seen = now
                )
                val response = api.registerDevice(device)
                if (!response.isSuccessful) {
                    Log.e(TAG, "Registro rechazado: HTTP ${response.code()}")
                    return@launch
                }
                val token = response.body()?.agent_token
                if (!token.isNullOrBlank()) {
                    if (!AgentTokenStore.saveToken(this@CommandService, token)) {
                        Log.e(TAG, "No se pudo guardar la credencial de forma cifrada")
                        return@launch
                    }
                }
                lastRegistrationAt = System.currentTimeMillis()
                Log.d(TAG, "Equipo registrado: ${config.deviceName} (${config.deviceId})")
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando equipo: ${e.message}")
            } finally {
                registrationInProgress.set(false)
            }
        }
    }

    // ====================== POLLING ======================

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                try {
                    if (System.currentTimeMillis() - lastRegistrationAt >= 5 * 60 * 1000L) registerDevice()
                    ParentalControlStore.expire(this@CommandService)
                    checkForCommands()
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling: ${e.message}")
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun checkForCommands() {
        if (config.deviceId.isEmpty()) return
        val token = AgentTokenStore.getToken(this)
        if (token.isNullOrBlank()) {
            registerDevice()
            return
        }
        val response = api.getTasks(config.deviceId, "queued")
        if (response.isSuccessful) {
            val tasks = response.body() ?: emptyList()
            for (task in tasks) {
                executeTask(task)
            }
        }
    }

    // ====================== EJECUCIÓN DE TAREAS ======================

    private suspend fun executeTask(task: Task) {
        reportTask(task.id, "in_progress")
        val (resultJson, errorMsg) = when (task.type) {
            "install" -> runCatching { installApp(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "uninstall" -> runCatching { uninstallApp(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "launch" -> runCatching { launchApp(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "list_apps" -> runCatching { listInstalledApps() }.fold(
                { it to null },
                { "null" to it.message }
            )
            "device_info" -> runCatching { getDeviceInfo() }.fold(
                { it to null },
                { "null" to it.message }
            )
            "pin_app" -> runCatching { pinAppToLauncher(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "notify" -> runCatching { handleNotify(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "kiosk_enable" -> runCatching { enableKiosk(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "kiosk_disable" -> runCatching { disableKiosk() }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "block_app" -> runCatching { setAppBlocked(task.payload, true) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "unblock_app" -> runCatching { setAppBlocked(task.payload, false) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "get_usage" -> runCatching { getUsageReport(task.payload) }.fold(
                { it to null },
                { "null" to it.message }
            )
            "reboot" -> runCatching { rebootDevice() }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            else -> ("null" to "Tipo de tarea desconocido: ${task.type}")
        }

        val finalStatus = if (errorMsg != null) "failed" else "completed"
        reportTask(task.id, finalStatus, result = resultJson, error = errorMsg)
        updateNotification(finalStatus, task.type)
    }

    /** Reporta estado/resultado al backend (PATCH /api/tasks/{id}). */
    private suspend fun reportTask(id: String, status: String, result: String? = null, error: String? = null) {
        try {
            api.updateTask(TaskUpdate(id = id, status = status, result = result, error = error))
        } catch (e: Exception) {
            Log.e(TAG, "Error reportando tarea $id: ${e.message}")
        }
    }

    // ====================== INSTALAR APK ======================

    /**
     * Instalación remota: descarga el APK desde la URL (Supabase Storage)
     * y lo instala con la API PackageInstaller (UI de confirmación en TV).
     */
    private suspend fun installApp(apkUrl: String): String {
        if (apkUrl.isBlank()) throw IllegalStateException("URL de APK vacía")

        val apkFile = downloadApk(apkUrl)
        val apkUri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )

        return installWithPackageInstaller(apkUri)
    }

    private suspend fun downloadApk(url: String): File {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Error descargando APK: HTTP ${response.code}")

        val body = response.body ?: throw IOException("Cuerpo vacío en la respuesta")
        val target = File(cacheDir, "pending_install_${System.currentTimeMillis()}.apk")
        body.byteStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        if (!target.exists() || target.length() == 0L) throw IOException("APK descargado vacío")
        return target
    }

    /**
     * Instalación con PackageInstaller Session API.
     * En Android TV muestra el diálogo de confirmación del sistema (navegable con mando).
     */
    private fun installWithPackageInstaller(apkUri: Uri): String {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        contentResolver.openInputStream(apkUri)?.use { input ->
            val out = session.openWrite("dexter_apk", 0, -1)
            input.copyTo(out)
            session.fsync(out)
            try { out.close() } catch (_: Exception) {}
        }

        val intent = Intent(this, InstallResultReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )
        session.commit(pendingIntent.intentSender)

        session.close()
        return "Instalación iniciada (confirma en pantalla del equipo)"
    }

    // ====================== DESINSTALAR ======================

    private fun uninstallApp(packageName: String): String {
        if (packageName.isBlank()) throw IllegalStateException("Nombre de paquete vacío")
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // En Android TV esto muestra el diálogo de desinstalación
        startActivity(intent)
        return "Solicitud de desinstalación enviada: $packageName (confirma en pantalla)"
    }

    // ====================== ABRIR APP ======================

    private fun launchApp(packageName: String): String {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("No se encontró actividad principal para $packageName")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return "Abriendo $packageName"
    }

    // ====================== LISTAR APPS ======================

    private fun listInstalledApps(): String {
        val packages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val apps = packages.mapNotNull { appInfo ->
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val label = appInfo.loadLabel(packageManager)?.toString() ?: appInfo.packageName
            // Excluir el propio agente y apps del sistema opcionalmente (se incluyen todas, el controlador filtra)
            mapOf(
                "packageName" to appInfo.packageName,
                "appName" to label,
                "isSystemApp" to isSystem,
                "sourceDir" to (appInfo.sourceDir ?: "")
            )
        }.sortedBy { (it["appName"] as String).lowercase() }

        return apps.toJsonArray()
    }

    // ====================== INFO DEL EQUIPO ======================

    private fun getDeviceInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.totalBytes
        val availBytes = stat.availableBytes

        val ip = try {
            val wm = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcp = wm.dhcpInfo
            String.format(
                "%d.%d.%d.%d",
                dhcp.ipAddress and 0xff, dhcp.ipAddress shr 8 and 0xff,
                dhcp.ipAddress shr 16 and 0xff, dhcp.ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) { "desconocida" }

        val info = mapOf(
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "manufacturer" to Build.MANUFACTURER,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdkInt" to Build.VERSION.SDK_INT,
            "isFireOS" to Build.MANUFACTURER.contains("Amazon", ignoreCase = true),
            "ipAddress" to ip,
            "totalRamBytes" to memInfo.totalMem,
            "availRamBytes" to memInfo.availMem,
            "totalStorageBytes" to totalBytes,
            "availStorageBytes" to availBytes,
            "uptimeMillis" to SystemClock.elapsedRealtime(),
            "batteryPct" to getBatteryLevel(),
            "installedAppsCount" to packageManager.getInstalledApplications(0).size,
            "kioskEnabled" to KioskManager.isEnabled(this),
            "deviceOwner" to DevicePolicyHelper.isDeviceOwner(this)
        )
        return info.toJson()
    }

    private fun getBatteryLevel(): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val status = registerReceiver(null, filter) ?: return -1
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0) level * 100 / scale else -1
        } catch (e: Exception) { -1 }
    }

    // ====================== PIN AL LAUNCHER ======================

    /**
     * Intenta fijar la app al inicio del launcher (Android TV Leanback / Fire TV).
     * Nota: sin privilegios de sistema no se puede garantizar el orden exacto;
     * se intentan los intents conocidos y se reporta el resultado.
     */
    private fun pinAppToLauncher(packageName: String): String {
        val attempts = mutableListOf<String>()

        // 1. Broadcast estándar de nueva app instalada (el launcher lo escucha)
        try {
            sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
            attempts.add("PACKAGE_ADDED broadcast enviado")
        } catch (e: Exception) { attempts.add("broadcast falló: ${e.message}") }

        // 2. Intent de pinning del Leanback Launcher (Android TV de Google)
        try {
            val pinIntent = Intent("com.google.android.leanbacklauncher.action.APP_PINNING")
                .setPackage("com.google.android.leanbacklauncher")
                .putExtra("packageName", packageName)
            sendBroadcast(pinIntent)
            attempts.add("Leanback pin intent enviado")
        } catch (e: Exception) { attempts.add("leanback pin falló: ${e.message}") }

        // 3. Intent de Fire TV (Amazon)
        try {
            val fireIntent = Intent("com.amazon.tv.leanbacklauncher.action.APP_PINNING")
                .setPackage("com.amazon.tv.leanbacklauncher")
                .putExtra("packageName", packageName)
            sendBroadcast(fireIntent)
            attempts.add("Fire TV pin intent enviado")
        } catch (e: Exception) { attempts.add("fire pin falló: ${e.message}") }

        return "Intentos de fijar en launcher: ${attempts.joinToString(" | ")}. " +
                "Nota: en Android TV/Fire Stick la app aparece en la fila de apps del launcher " +
                "tras la instalación; para fijarla arriba usa el mando (seleccionar app → mantener → Fijar)."
    }

    // ====================== REBOOT ======================

    private fun rebootDevice(): String {
        return try {
            val pm = packageManager
            val hasReboot = pm.checkPermission("android.permission.REBOOT", packageName) == PackageManager.PERMISSION_GRANTED
            if (hasReboot) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.reboot(null)
                "Reiniciando equipo..."
            } else {
                // Sin permiso REBOOT (app normal): mostrar diálogo de apagado del sistema
                val intent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN").apply {
                    putExtra("android.intent.extra.KEY_CONFIRM", false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                "Sin permiso REBOOT: se mostró el diálogo de apagado del sistema (confirma en pantalla)"
            }
        } catch (e: Exception) {
            throw IllegalStateException("No se pudo reiniciar: ${e.message}")
        }
    }

    // ====================== COMANDOS AVANZADOS ======================

    private fun handleNotify(payload: String): String {
        val data = runCatching { JSONObject(payload) }.getOrNull()
        val title = data?.optString("title")?.takeIf { it.isNotBlank() } ?: "Dexterh4ck Control"
        val body = data?.optString("message")?.takeIf { it.isNotBlank() }
            ?: data?.optString("body")?.takeIf { it.isNotBlank() }
            ?: payload
        NotifyHelper.showCommandNotification(this, title, body)
        return "Notificación mostrada"
    }

    private fun enableKiosk(payload: String): String {
        val data = runCatching { JSONObject(payload) }.getOrNull()
        val allowed = data?.optJSONArray("allowedPackages") ?: data?.optJSONArray("packages")
        val packages = if (allowed != null) {
            (0 until allowed.length()).mapNotNull { i ->
                allowed.optString(i).takeIf { it.isNotBlank() }
            }.joinToString(",")
        } else {
            data?.optString("allowedPackages", "") ?: ""
        }
        DevicePolicyHelper.setLockTaskWhitelist(this)
        KioskManager.enable(this, packages)
        return "Modo kiosk activado"
    }

    private fun disableKiosk(): String {
        KioskManager.disable(this)
        return "Modo kiosk desactivado"
    }

    private fun setAppBlocked(payload: String, blocked: Boolean): String {
        val data = runCatching { JSONObject(payload) }.getOrNull()
        val packageName = data?.optString("packageName")?.takeIf { it.isNotBlank() }
            ?: data?.optString("package")?.takeIf { it.isNotBlank() }
            ?: payload.trim()
        require(packageName.isNotBlank()) { "Nombre de paquete vacío" }
        return if (blocked) {
            val pin = data?.optString("pin", "") ?: ""
            val minutes = data?.optInt("durationMinutes", 60) ?: 60
            ParentalControlStore.block(this, packageName, pin, minutes)
        } else {
            val pin = data?.optString("pin", "") ?: ""
            ParentalControlStore.unblock(this, packageName, pin)
        }
    }

    private fun getUsageReport(payload: String): String {
        val data = runCatching { JSONObject(payload) }.getOrNull()
        val hours = (data?.optInt("hours", 24) ?: 24).coerceIn(1, 168)
        return UsageStatsHelper.getUsageReport(this, hours)
    }

    // ====================== UTILIDADES ======================

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun Map<*, *>.toJson(): String {
        val entries = entries.joinToString(",") { "\"${it.key}\":${toJsonValue(it.value)}" }
        return "{$entries}"
    }

    private fun List<Map<String, Any>>.toJsonArray(): String {
        val items = joinToString(",") { it.toJson() }
        return "[$items]"
    }

    private fun toJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escapeJson(value)}\""
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> "\"${escapeJson(value.toString())}\""
    }

    private fun updateNotification(status: String, taskType: String) {
        val msg = when (status) {
            "completed" -> "✔ $taskType completado"
            "failed" -> "✘ $taskType falló"
            else -> "⏳ $taskType en progreso"
        }
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(msg))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val channelId = CHANNEL_ID
        val notificationIntent = Intent(this, com.dexterh4ck.equipos.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Dexterh4ck Equipos")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dexterh4ck Equipos",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Servicio de gestión remota" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

package com.dexterh4ck.equipos.service

import android.app.*
import android.content.*
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.*
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.dexterh4ck.equipos.data.*
import com.dexterh4ck.equipos.util.DevicePolicyHelper
import com.dexterh4ck.equipos.data.WireGuardKeyStore
import com.dexterh4ck.equipos.vpn.WireGuardTunnelManager
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
 * Mantiene un servicio foreground persistente, renueva el registro cada 30 segundos y consulta tareas cada 5 segundos
 * (install, uninstall, launch, list_apps, device_info, reboot, pin_app),
 * las ejecuta en el dispositivo y reporta el resultado.
 */
class CommandService : Service() {

    companion object {
        private const val TAG = "DexterEquipos"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "dexter_equipos_channel"
        private const val PENDING_INSTALL_CHANNEL_ID = "dexter_pending_install_channel"
        private const val PENDING_INSTALL_RETENTION_MS = 48L * 60L * 60L * 1000L
        private const val POLL_INTERVAL = 5_000L
        private const val CONNECT_TIMEOUT = 30L
        private const val READ_TIMEOUT = 60L
        const val ACTION_INSTALL_RESULT = "com.dexterh4ck.equipos.ACTION_INSTALL_RESULT"
        const val EXTRA_INSTALL_TASK_ID = "install_task_id"
        const val EXTRA_INSTALL_STATE = "install_state"
        const val EXTRA_INSTALL_MESSAGE = "install_message"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val registrationInProgress = AtomicBoolean(false)
    @Volatile private var lastRegistrationAt = 0L
    private lateinit var config: AgentConfig
    private lateinit var api: KioskApi
    private var agentWakeLock: PowerManager.WakeLock? = null
    private var vpnTunnelManager: WireGuardTunnelManager? = null
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
        startStoredVpnIfAuthorized()
        registerDevice()
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Dexterh4ck Equipos activo"))
        acquireAgentWakeLock()
        if (intent?.action == PendingInstallActionReceiver.ACTION_ACCEPT) {
            val taskId = intent.getStringExtra(PendingInstallActionReceiver.EXTRA_TASK_ID)
            if (!taskId.isNullOrBlank()) scope.launch { acceptPendingInstall(taskId) }
        }
        if (intent?.action == ACTION_INSTALL_RESULT) {
            val taskId = intent.getStringExtra(EXTRA_INSTALL_TASK_ID)
            val state = intent.getStringExtra(EXTRA_INSTALL_STATE)
            val message = intent.getStringExtra(EXTRA_INSTALL_MESSAGE).orEmpty()
            if (!taskId.isNullOrBlank()) scope.launch { handleInstallResult(taskId, state, message) }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { if (agentWakeLock?.isHeld == true) agentWakeLock?.release() }
        agentWakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    /** Mantiene CPU despierta para el heartbeat del TV/Box mientras el servicio foreground está activo. */
    private fun acquireAgentWakeLock() {
        if (agentWakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        agentWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:AgentHeartbeat").apply {
            setReferenceCounted(false)
            acquire()
        }
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

    private fun startStoredVpnIfAuthorized() {
        if (VpnService.prepare(this) != null) return
        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("vpn_enabled", false)) return
        val ip = prefs.getString("assigned_vpn_ip", null)?.substringBefore('/').orEmpty()
        val endpoint = prefs.getString("vpn_endpoint", null).orEmpty()
        val serverKey = prefs.getString("vpn_server_public_key", null).orEmpty()
        if (ip.isBlank() || endpoint.isBlank() || serverKey.isBlank()) return
        scope.launch {
            runCatching {
                val pair = WireGuardKeyStore.getOrCreate(this@CommandService)
                val wireGuardConfig = """
                    [Interface]
                    PrivateKey = ${pair.privateKey.toBase64()}
                    Address = $ip/32
                    MTU = 1280

                    [Peer]
                    PublicKey = $serverKey
                    Endpoint = $endpoint
                    AllowedIPs = 10.88.0.0/16
                    PersistentKeepalive = 25
                """.trimIndent()
                vpnTunnelManager = WireGuardTunnelManager(this@CommandService)
                vpnTunnelManager!!.connect(wireGuardConfig)
                Log.i(TAG, "WireGuard tunnel restored for $ip")
            }.onFailure {
                Log.e(TAG, "WireGuard restore failed: ${it.message}")
            }
        }
    }

    // ====================== REGISTRO DEL EQUIPO ======================

    private fun registerDevice() {
        if (!registrationInProgress.compareAndSet(false, true)) return
        lastRegistrationAt = System.currentTimeMillis()
        scope.launch {
            try {
                val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val now = timestampFormat.format(Date())
                val device = DeviceRegistration(
                    device_id = config.deviceId,
                    hardware_id = config.deviceId,
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
                    if (System.currentTimeMillis() - lastRegistrationAt >= 30_000L) registerDevice()
                    expireOldPendingInstalls()
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
        if (task.type == "install" && !DevicePolicyHelper.isDeviceOwner(this)) {
            queueInstallForUserAcceptance(task)
            return
        }
        reportTask(task.id, "in_progress")
        val (resultJson, errorMsg) = when (task.type) {
            "install" -> runCatching { installApp(task.id, task.payload) }.fold(
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
            "adb_command" -> runCatching { runAdbDiagnostic(task.payload) }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            "reboot" -> runCatching { rebootDevice() }.fold(
                { "\"${escapeJson(it)}\"" to null },
                { "null" to it.message }
            )
            else -> ("null" to "Tipo de tarea desconocido: ${task.type}")
        }

        val finalStatus = when {
            errorMsg != null -> "failed"
            task.type == "install" -> "in_progress" // El estado terminal llega desde InstallResultReceiver.
            else -> "completed"
        }
        val finalResult = if (task.type == "install" && errorMsg == null) {
            JSONObject().apply {
                put("phase", "install")
                put("progress", 99)
                put("message", "APK transferido a PackageInstaller; esperando confirmación del sistema.")
            }.toString()
        } else resultJson
        reportTask(task.id, finalStatus, result = finalResult, error = errorMsg)
        updateNotification(finalStatus, task.type)
    }

    private suspend fun handleInstallResult(taskId: String, state: String?, message: String) {
        when (state) {
            "success" -> {
                reportTask(taskId, "completed", result = JSONObject().apply {
                    put("phase", "installed")
                    put("progress", 100)
                    put("message", message.ifBlank { "Instalación completada en el equipo." })
                }.toString())
                updateNotification("completed", "install")
            }
            "pending" -> reportTask(taskId, "in_progress", result = JSONObject().apply {
                put("phase", "install")
                put("progress", 99)
                put("message", message.ifBlank { "Esperando confirmación de Android en la pantalla del equipo." })
            }.toString())
            else -> {
                reportTask(taskId, "failed", error = message.ifBlank { "Android no pudo completar la instalación." })
                updateNotification("failed", "install")
            }
        }
    }

    private fun pendingInstallPrefs() = getSharedPreferences("pending_user_installs", Context.MODE_PRIVATE)

    private fun pendingInstallNotificationId(taskId: String) = taskId.hashCode() and 0x7fffffff

    private fun parseServerExpiry(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        // java.text.SimpleDateFormat's X timezone token is unavailable on some API 21-23 TVs.
        val normalized = value.replace(Regex("([+-]\\d{2}):(\\d{2})$")) { match ->
            match.groupValues[1] + match.groupValues[2]
        }.replace(Regex("Z$"), "+0000")
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")
        for (pattern in formats) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                parser.parse(normalized)?.let { return it.time }
            } catch (_: Exception) { }
        }
        return 0L
    }

    private suspend fun queueInstallForUserAcceptance(task: Task) {
        val prefs = pendingInstallPrefs()
        val key = "task_${task.id}"
        val existingExpiry = prefs.getLong("${key}_expires", 0L)
        val serverExpiry = parseServerExpiry(task.expires_at)
        val expiresAt = when {
            existingExpiry > 0L -> existingExpiry
            serverExpiry > 0L -> serverExpiry
            else -> System.currentTimeMillis() + PENDING_INSTALL_RETENTION_MS
        }
        if (expiresAt <= System.currentTimeMillis()) {
            expirePendingInstall(task.id)
            return
        }
        try {
            // Descarga primero al almacenamiento privado del TV; después Control puede borrar el temporal en la nube.
            val apkFile = downloadApk(task.id, task.payload)
            // Persistir primero el archivo local para que un reinicio no pierda la referencia.
            prefs.edit().putString("${key}_file", apkFile.absolutePath)
                .putLong("${key}_expires", expiresAt).apply()
            val reported = reportTask(task.id, "awaiting_user", result = JSONObject().apply {
                put("phase", "downloaded")
                put("progress", 100)
                put("message", "APK descargado localmente; esperando aceptación en la TV.")
            }.toString(), error = "Esperando aceptación local; vence a las 48 horas.")
            prefs.edit().putBoolean("${key}_reported", reported).apply()
            showPendingInstallNotification(task.id, apkFile.name, expiresAt)
        } catch (error: Exception) {
            reportTask(task.id, "failed", error = "No se pudo descargar el APK: ${error.message}")
        }
    }

    private fun showPendingInstallNotification(taskId: String, apkUrl: String, expiresAt: Long) {
        val notificationId = pendingInstallNotificationId(taskId)
        val accept = Intent(this, PendingInstallActionReceiver::class.java).apply {
            action = PendingInstallActionReceiver.ACTION_ACCEPT
            putExtra(PendingInstallActionReceiver.EXTRA_TASK_ID, taskId)
        }
        val acceptPending = PendingIntent.getBroadcast(
            this, notificationId, accept,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, notificationId, Intent(this, com.dexterh4ck.equipos.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val remainingHours = ((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L) / 3600000L)
        val notification = NotificationCompat.Builder(this, PENDING_INSTALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Instalación pendiente de autorización")
            .setContentText("Acepta instalar la aplicación. Caduca en $remainingHours h.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Android no tiene Device Owner activo. Acepta la instalación desde esta notificación. La solicitud se cancelará automáticamente al cumplir 48 horas."))
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_input_add, "Aceptar instalación", acceptPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, notification)
    }

    private suspend fun acceptPendingInstall(taskId: String) {
        val prefs = pendingInstallPrefs()
        val key = "task_$taskId"
        val localPath = prefs.getString("${key}_file", null)
        val expiry = prefs.getLong("${key}_expires", 0L)
        if (localPath.isNullOrBlank()) return
        if (System.currentTimeMillis() >= expiry) {
            expirePendingInstall(taskId)
            return
        }
        val apkFile = File(localPath)
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            expirePendingInstall(taskId)
            return
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(pendingInstallNotificationId(taskId))
        prefs.edit().remove("${key}_file").remove("${key}_expires").remove("${key}_reported").apply()
        reportTask(taskId, "in_progress")
        try {
            val result = installApkFile(taskId, apkFile)
            reportTask(taskId, "in_progress", result = JSONObject().apply {
                put("phase", "install")
                put("progress", 99)
                put("message", result)
            }.toString())
            updateNotification("in_progress", "install")
        } catch (e: Exception) {
            reportTask(taskId, "failed", error = e.message ?: "Error instalando APK")
            updateNotification("failed", "install")
        } finally {
            apkFile.delete()
        }
    }

    private suspend fun expirePendingInstall(taskId: String) {
        val key = "task_$taskId"
        val prefs = pendingInstallPrefs()
        prefs.getString("${key}_file", null)?.let { File(it).delete() }
        prefs.edit().remove("${key}_file").remove("${key}_expires").remove("${key}_reported").apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(pendingInstallNotificationId(taskId))
        reportTask(taskId, "expired", error = "Solicitud cancelada: no fue aceptada en 48 horas.")
    }

    private suspend fun expireOldPendingInstalls() {
        val prefs = pendingInstallPrefs()
        val now = System.currentTimeMillis()
        prefs.all.keys.filter { it.endsWith("_expires") }.forEach { expiryKey ->
            val expires = prefs.getLong(expiryKey, 0L)
            val taskId = expiryKey.removePrefix("task_").removeSuffix("_expires")
            if (expires > 0L && now >= expires) expirePendingInstall(taskId)
            else {
                val localPath = prefs.getString("task_${taskId}_file", null)
                if (!localPath.isNullOrBlank()) showPendingInstallNotification(taskId, File(localPath).name, expires)
            }
        }
    }

    /** Reporta estado/resultado al backend (PATCH /api/tasks/{id}). */
    private suspend fun reportTask(id: String, status: String, result: String? = null, error: String? = null): Boolean {
        return try {
            val response = api.updateTask(TaskUpdate(id = id, status = status, result = result, error = error))
            if (!response.isSuccessful) Log.e(TAG, "Backend rechazó estado $status de tarea $id: HTTP ${response.code()}")
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Error reportando tarea $id: ${e.message}")
            false
        }
    }

    // ====================== INSTALAR APK ======================

    /**
     * Instalación remota: descarga el APK desde la URL (Supabase Storage)
     * y lo instala con la API PackageInstaller (UI de confirmación en TV).
     */
    private suspend fun installApp(taskId: String, apkUrl: String): String {
        if (apkUrl.isBlank()) throw IllegalStateException("URL de APK vacía")
        reportInstallProgress(taskId, "download", 0, 0L)
        val apkFile = downloadApk(taskId, apkUrl)
        reportInstallProgress(taskId, "downloaded", 100, apkFile.length())
        return installApkFile(taskId, apkFile)
    }

    private suspend fun installApkFile(taskId: String, apkFile: File): String {
        if (!apkFile.isFile || apkFile.length() <= 0L) throw IOException("El APK local no existe o está vacío")
        try {
            reportInstallProgress(taskId, "install", 90, apkFile.length())
            val apkUri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            return installWithPackageInstaller(apkUri, taskId, apkFile.length())
        } finally {
            apkFile.delete()
        }
    }

    private suspend fun reportInstallProgress(taskId: String, phase: String, percent: Int, totalBytes: Long) {
        val payload = JSONObject().apply {
            put("phase", phase)
            put("progress", percent.coerceIn(0, 100))
            put("totalBytes", totalBytes)
        }.toString()
        reportTask(taskId, "in_progress", result = payload)
    }

    private suspend fun downloadApk(taskId: String, url: String): File {
        val request = Request.Builder().url(url).get().build()
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Error descargando APK: HTTP ${response.code}")
        }
        response.use { httpResponse ->
            val body = httpResponse.body ?: throw IOException("Cuerpo vacío en la respuesta")
            val total = body.contentLength()
            val target = File(cacheDir, "pending_install_${System.currentTimeMillis()}.apk")
            try {
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val percent = if (total > 0L) ((downloaded * 90L) / total).toInt().coerceIn(0, 90) else 0
                            if (percent != lastReported && (percent % 5 == 0 || percent == 90)) {
                                reportInstallProgress(taskId, "download", percent, total)
                                lastReported = percent
                            }
                        }
                    }
                }
                if (!target.exists() || target.length() == 0L) throw IOException("APK descargado vacío")
                reportInstallProgress(taskId, "install", 90, total)
                return target
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }
    }

    private suspend fun installWithPackageInstaller(apkUri: Uri, taskId: String, totalBytes: Long): String {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            contentResolver.openInputStream(apkUri)?.use { input ->
                session.openWrite("dexter_apk", 0, totalBytes).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastReported = 90
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        copied += count
                        val percent = if (totalBytes > 0L) (90 + copied * 9L / totalBytes).toInt().coerceIn(90, 99) else 95
                        if (percent != lastReported && percent % 2 == 0) {
                            reportInstallProgress(taskId, "install", percent, totalBytes)
                            lastReported = percent
                        }
                    }
                    session.fsync(output)
                }
            } ?: throw IOException("No se pudo abrir el APK temporal")
            val intent = Intent(this, InstallResultReceiver::class.java).apply {
                putExtra("task_id", taskId)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            )
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
        reportInstallProgress(taskId, "install", 99, totalBytes)
        return if (DevicePolicyHelper.isDeviceOwner(this))
            "APK descargado y enviado a PackageInstaller. Device Owner activo; instalación silenciosa solicitada."
        else "APK descargado y enviado a PackageInstaller. Android solicitará confirmación en pantalla."
    }

    // ====================== DESINSTALAR ======================

    private fun uninstallApp(packageName: String): String {
        require(packageName.matches(Regex("^[A-Za-z0-9_.]+$"))) { "Nombre de paquete inválido" }
        if (DevicePolicyHelper.isDeviceOwner(this)) {
            val callback = Intent(this, InstallResultReceiver::class.java)
            val pending = PendingIntent.getBroadcast(this, packageName.hashCode(), callback,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0))
            packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
            return "Desinstalación silenciosa enviada al sistema como Device Owner: $packageName"
        }
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        return "Android requiere confirmar la desinstalación en pantalla: $packageName"
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


    /** Ejecuta sólo diagnósticos ADB predefinidos y únicamente si el equipo ofrece su binario su. */
    private fun runAdbDiagnostic(payload: String): String {
        val command = payload.trim()
        val allowed = setOf(
            "getprop", "df -h", "free -h", "dumpsys battery",
            "dumpsys meminfo", "pm list packages", "wm size", "wm density",
            "settings get global airplane_mode_on", "settings get global stay_on_while_plugged_in"
        )
        require(command in allowed) { "Comando no permitido. Usa uno de los diagnósticos disponibles." }
        val outputFile = File(cacheDir, "adb_diagnostic_${System.currentTimeMillis()}.txt")
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).redirectOutput(outputFile).start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputFile.delete()
            throw IllegalStateException("El comando excedió 15 segundos")
        }
        val output = outputFile.takeIf { it.exists() }?.readText()?.take(8000).orEmpty()
        outputFile.delete()
        if (process.exitValue() != 0) {
            throw IllegalStateException(output.ifBlank { "No se pudo ejecutar. El equipo necesita root/su." })
        }
        return output.ifBlank { "Comando ejecutado sin salida." }
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
        KioskManager.enable(this, packages)
        DevicePolicyHelper.setLockTaskWhitelist(this)
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
            val pin = data?.optString("pinHash", "") ?: ""
            val minutes = data?.optInt("durationMinutes", 60) ?: 60
            ParentalControlStore.block(this, packageName, pin, minutes)
        } else {
            val pin = data?.optString("pinHash", "") ?: ""
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
            val pendingChannel = NotificationChannel(
                PENDING_INSTALL_CHANNEL_ID,
                "Instalaciones pendientes",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Solicitudes de instalación que requieren aceptación local"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(pendingChannel)
        }
    }
}

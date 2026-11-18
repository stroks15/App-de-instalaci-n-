package com.dexterh4ck.control.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.InputType
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.control.R
import com.dexterh4ck.control.adapter.InstalledAppAdapter
import com.dexterh4ck.control.data.*
import org.json.JSONObject
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Pantalla de gestión de un equipo: apps, tareas y administración remota. */
class DeviceDetailActivity : AppCompatActivity() {
    private lateinit var api: KioskApi
    private lateinit var gson: Gson
    private var deviceId: String = ""
    private var deviceName: String = ""

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvLastSeen: TextView
    private lateinit var viewOnlineIndicator: View
    private lateinit var progressTask: ProgressBar
    private lateinit var tvTaskStatus: TextView
    private lateinit var tvTaskResult: TextView
    private lateinit var rvInstalledApps: RecyclerView
    private lateinit var tvNoApps: TextView
    private var appsAdapter: InstalledAppAdapter? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) uploadAndInstall(uri)
        else toast("No se seleccionó ningún APK")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)
        deviceId = intent.getStringExtra("device_id").orEmpty()
        deviceName = intent.getStringExtra("device_name") ?: "Equipo"
        if (deviceId.isBlank()) {
            toast("No se recibió el identificador del equipo. Vuelve a seleccionarlo.")
            finish()
            return
        }

        val retrofit = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this)).build()
        api = retrofit.create(KioskApi::class.java)
        gson = Gson()
        bindViews()
        setupActions()
        loadDeviceDetail()
        refreshAppsList()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bindViews() {
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvLastSeen = findViewById(R.id.tvLastSeen)
        viewOnlineIndicator = findViewById(R.id.viewOnlineIndicator)
        progressTask = findViewById(R.id.progressTask)
        tvTaskStatus = findViewById(R.id.tvTaskStatus)
        tvTaskResult = findViewById(R.id.tvTaskResult)
        rvInstalledApps = findViewById(R.id.rvInstalledApps)
        tvNoApps = findViewById(R.id.tvNoApps)
        tvDeviceName.text = deviceName
        tvDeviceId.text = deviceId
        rvInstalledApps.layoutManager = LinearLayoutManager(this)
    }

    private fun setupActions() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnInstallFromStorage).setOnClickListener { showInstallSourceDialog() }
        findViewById<Button>(R.id.btnRefreshApps).setOnClickListener { refreshAppsList() }
        findViewById<Button>(R.id.btnDeviceInfo).setOnClickListener { sendTask("device_info", "") }
        findViewById<Button>(R.id.btnAdbCommand).setOnClickListener { showAdbCommandDialog() }
        findViewById<Button>(R.id.btnReboot).setOnClickListener {
            confirmAction("¿Reiniciar el equipo?") { sendTask("reboot", "") }
        }
        findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            confirmAction("¿Eliminar este equipo de la lista?") { deleteDevice() }
        }
    }

    private fun showInstallSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Seleccionar origen del APK")
            .setItems(arrayOf("Elegir APK del teléfono", "Buscar APK en GitHub")) { _, which ->
                if (which == 0) apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                else startActivity(Intent(this, GithubApkActivity::class.java).apply {
                    putExtra("device_id", deviceId)
                    putExtra("device_name", deviceName)
                })
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadDeviceDetail() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { api.getDeviceDetail(deviceId) }
                if (response.isSuccessful) {
                    val detail = response.body()
                    detail?.device?.let { device ->
                        tvDeviceName.text = device.name
                        updateStatusUI(device.status)
                        tvLastSeen.text = "Última conexión: ${device.last_seen ?: "nunca"}"
                        detail.installed_apps?.let { showApps(it) }
                    }
                } else {
                    setTaskStatus("No se pudo cargar el equipo (HTTP ${response.code()})", false)
                }
            } catch (e: Exception) {
                setTaskStatus("Error al cargar detalle: ${e.message}", false)
            }
        }
    }

    private fun updateStatusUI(status: String) {
        tvDeviceStatus.text = "Estado: $status"
        val color = when (status) {
            "online" -> android.R.color.holo_green_dark
            "busy" -> android.R.color.holo_orange_dark
            else -> android.R.color.darker_gray
        }
        viewOnlineIndicator.setBackgroundColor(getColor(color))
    }

    private fun uploadAndInstall(uri: Uri) {
        setTaskStatus("Copiando APK...", true)
        scope.launch {
            val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.apk")
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Android no pudo abrir el archivo seleccionado")
                    input.use { source -> FileOutputStream(tempFile).use { output -> source.copyTo(output) } }
                }
                if (!tempFile.isFile || tempFile.length() < 1L) {
                    throw IllegalStateException("El archivo seleccionado está vacío o no es accesible")
                }
                if (tempFile.length() > 1024L * 1024L * 1024L) {
                    throw IllegalStateException("El APK supera el límite de 1 GB")
                }

                setTaskStatus("Subiendo APK al almacenamiento...", true)
                val fileBody = tempFile.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", tempFile.name, fileBody)
                val bucket = "apks".toRequestBody("text/plain".toMediaTypeOrNull())
                val upload = withContext(Dispatchers.IO) { api.uploadFile(part, bucket) }
                if (!upload.isSuccessful) {
                    throw IllegalStateException("Error al subir APK: HTTP ${upload.code()} ${upload.errorBody()?.string()?.take(180).orEmpty()}")
                }
                val apkUrl = upload.body()?.url?.takeIf { it.startsWith("https://") }
                    ?: throw IllegalStateException("El servidor no devolvió una URL HTTPS válida")
                setTaskStatus("Enviando instalación a $deviceName...", true)
                val taskResponse = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = "install", payload = apkUrl))
                }
                if (!taskResponse.isSuccessful || taskResponse.body() == null) {
                    throw IllegalStateException("No se pudo crear la tarea de instalación: HTTP ${taskResponse.code()}")
                }
                trackTask(taskResponse.body()!!.id, "Instalando APK en $deviceName...")
            } catch (e: Exception) {
                setTaskStatus("No se pudo instalar: ${e.message}", false)
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun sendTask(type: String, payload: String) {
        setTaskStatus("Enviando comando $type...", true)
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = type, payload = payload))
                }
                if (response.isSuccessful && response.body() != null) {
                    trackTask(response.body()!!.id, "Comando $type enviado")
                } else setTaskStatus("Error al enviar tarea: HTTP ${response.code()}", false)
            } catch (e: Exception) {
                setTaskStatus("Error: ${e.message}", false)
            }
        }
    }

    private fun trackTask(taskId: String, initialMsg: String) {
        scope.launch {
            setTaskStatus(initialMsg, true)
            var attempts = 0
            var lastStatus = ""
            while (attempts < 90) {
                delay(2000)
                attempts++
                try {
                    val response = withContext(Dispatchers.IO) { api.getTask(taskId) }
                    if (response.isSuccessful) {
                        val task = response.body()?.task ?: continue
                        if (task.status != lastStatus) {
                            lastStatus = task.status
                            setTaskStatus("Estado: ${task.status}", task.status != "completed" && task.status != "failed")
                        }
                        if (task.status == "completed" || task.status == "failed") {
                            setTaskStatus(if (task.status == "completed") "✔ Completado" else "✘ Falló", false)
                            tvTaskResult.text = parseResult(task) + if (task.error != null) "\nError: ${task.error}" else ""
                            if (task.type == "device_info" && task.status == "completed") renderDeviceResources(task.result)
                            if (task.type == "install" && task.status == "completed") {
                                delay(1500)
                                refreshAppsList()
                            }
                            return@launch
                        }
                    }
                } catch (_: Exception) { /* reintentar */ }
            }
            setTaskStatus("Tiempo de espera agotado; revisa el estado del equipo", false)
        }
    }

    private fun parseResult(task: Task): String {
        return try {
            val result = task.result ?: return "(sin resultado)"
            if (result.trim().startsWith("[")) {
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val list: List<Map<String, Any>> = gson.fromJson(result, type)
                "Resultado: ${list.size} elementos\n" + list.take(20).joinToString("\n") { it.toString() }
            } else if (result.trim().startsWith("{")) "Resultado:\n$result"
            else gson.fromJson(result, String::class.java)
        } catch (_: Exception) { task.result ?: "(sin resultado)" }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun showAdbCommandDialog() {
        val commands = arrayOf(
            "getprop", "df -h", "free -h", "dumpsys battery", "dumpsys meminfo",
            "pm list packages", "wm size", "wm density",
            "settings get global airplane_mode_on", "settings get global stay_on_while_plugged_in"
        )
        AlertDialog.Builder(this).setTitle("Diagnóstico ADB")
            .setMessage("Selecciona un comando de diagnóstico. La ejecución requiere que el equipo admita el servicio de diagnóstico.")
            .setItems(commands) { _, which -> sendTask("adb_command", commands[which]) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun renderDeviceResources(raw: String?) {
        val target = findViewById<TextView>(R.id.tvDeviceResources)
        try {
            val data = JSONObject(raw ?: "{}")
            val totalRam = data.optLong("totalRamBytes")
            val freeRam = data.optLong("availRamBytes")
            val totalStorage = data.optLong("totalStorageBytes")
            val freeStorage = data.optLong("availStorageBytes")
            target.text = "RAM: ${formatBytes(freeRam)} libres de ${formatBytes(totalRam)}\n" +
                "Almacenamiento: ${formatBytes(freeStorage)} libres de ${formatBytes(totalStorage)}\n" +
                "Modelo: ${data.optString("manufacturer")} ${data.optString("model")} · Android ${data.optString("androidVersion")}"
        } catch (e: Exception) {
            target.text = "No se pudo interpretar la información: ${e.message}"
        }
    }

    private fun formatBytes(value: Long): String {
        val gb = value / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(java.util.Locale.US, "%.2f GB", gb)
        else String.format(java.util.Locale.US, "%.0f MB", value / (1024.0 * 1024.0))
    }

    private fun refreshAppsList() {
        setTaskStatus("Solicitando lista de apps al equipo...", true)
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = "list_apps", payload = ""))
                }
                if (!created.isSuccessful || created.body() == null) {
                    setTaskStatus("No se pudo solicitar la lista de apps (HTTP ${created.code()})", false)
                    return@launch
                }
                val taskId = created.body()!!.id
                var attempts = 0
                var completed = false
                while (attempts < 30 && !completed) {
                    delay(2000)
                    attempts++
                    val taskResponse = withContext(Dispatchers.IO) { api.getTask(taskId) }
                    if (taskResponse.isSuccessful) {
                        when (taskResponse.body()?.task?.status) {
                            "completed" -> completed = true
                            "failed" -> {
                                setTaskStatus("El equipo reportó un error al listar apps", false)
                                return@launch
                            }
                        }
                    }
                }
                if (!completed) {
                    setTaskStatus("El equipo tarda en responder; intenta actualizar más tarde", false)
                    return@launch
                }
                val appsResponse = withContext(Dispatchers.IO) { api.getInstalledApps(deviceId) }
                if (appsResponse.isSuccessful) {
                    val apps = appsResponse.body()?.apps.orEmpty()
                    showApps(apps)
                    setTaskStatus("Lista de apps actualizada (${apps.size} apps)", false)
                } else setTaskStatus("No se pudo obtener la lista de apps (HTTP ${appsResponse.code()})", false)
            } catch (e: Exception) {
                setTaskStatus("Error al actualizar apps: ${e.message}", false)
            }
        }
    }

    private fun showApps(apps: List<InstalledApp>) {
        if (apps.isEmpty()) {
            tvNoApps.visibility = View.VISIBLE
            appsAdapter = null
            rvInstalledApps.adapter = null
            return
        }
        tvNoApps.visibility = View.GONE
        val sorted = apps.sortedBy { it.appName.lowercase() }
        appsAdapter = InstalledAppAdapter(sorted,
            onOpenClick = { app -> sendTask("launch", app.packageName) },
            onUninstallClick = { app ->
                confirmAction("¿Desinstalar ${app.appName} de $deviceName?") {
                    sendTask("uninstall", app.packageName)
                }
            },
            onPinClick = { app ->
                sendTask("pin_app", app.packageName)
                toast("Intentando fijar ${app.appName} en el launcher del equipo")
            },
            onParentalClick = { app -> showParentalDialog(app) }
        )
        rvInstalledApps.adapter = appsAdapter
        appsAdapter?.updateApps(sorted)
    }

    private fun showParentalDialog(app: InstalledApp) {
        val options = arrayOf(
            "Bloquear por 15 minutos", "Bloquear por 30 minutos", "Bloquear por 1 hora",
            "Bloquear por 2 horas", "Bloquear por 4 horas",
            "Bloquear hasta desactivar desde Control", "Desbloquear"
        )
        AlertDialog.Builder(this).setTitle("Control parental · ${app.appName}")
            .setItems(options) { _, which ->
                if (which == 6) showParentalPinDialog(app, false, 0)
                else {
                    val minutes = when (which) { 0 -> 15; 1 -> 30; 2 -> 60; 3 -> 120; 4 -> 240; else -> 0 }
                    showParentalPinDialog(app, true, minutes)
                }
            }.show()
    }

    private fun showParentalPinDialog(app: InstalledApp, blocking: Boolean, minutes: Int) {
        val pin = EditText(this).apply {
            hint = "PIN parental de 4 a 8 dígitos"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            setTextColor(Color.BLACK)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(pin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(if (blocking) "Definir PIN y bloquear" else "Desbloquear aplicación")
            .setMessage(if (blocking) "La app quedará oculta en el equipo. Para desbloquear antes de tiempo se solicitará este PIN." else "Introduce el PIN parental para retirar el bloqueo.")
            .setView(container).setNegativeButton("Cancelar", null)
            .setPositiveButton(if (blocking) "Bloquear" else "Desbloquear") { _, _ ->
                val value = pin.text.toString()
                if (!value.matches(Regex("^\\d{4,8}$"))) {
                    toast("El PIN debe tener de 4 a 8 dígitos")
                    return@setPositiveButton
                }
                val payload = gson.toJson(mapOf(
                    "packageName" to app.packageName,
                    "pinHash" to sha256(value),
                    "durationMinutes" to minutes
                ))
                sendTask(if (blocking) "block_app" else "unblock_app", payload)
            }.show()
    }

    private fun setTaskStatus(message: String, inProgress: Boolean) {
        tvTaskStatus.text = message
        progressTask.visibility = if (inProgress) View.VISIBLE else View.GONE
    }

    private fun confirmAction(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton("Confirmar") { _, _ -> onConfirm() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun deleteDevice() {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) { api.deleteDevice(deviceId) }
                if (response.isSuccessful) {
                    toast("Equipo eliminado de la lista")
                    finish()
                } else toast("Error al eliminar: HTTP ${response.code()}")
            } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

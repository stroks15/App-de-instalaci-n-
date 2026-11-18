package com.dexterh4ck.control.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import android.text.InputType
import android.view.ViewGroup
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Pantalla de gestión de un equipo: instalar APK (subida real a Supabase Storage),
 * desinstalar apps, abrir apps, fijar en launcher, reiniciar y ver apps instaladas.
 */
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
    private lateinit var btnRefreshApps: Button

    private var appsAdapter: InstalledAppAdapter? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadAndInstall(uri)
        } else {
            toast("No se seleccionó ningún APK")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_detail)

        deviceId = intent.getStringExtra("device_id") ?: ""
        deviceName = intent.getStringExtra("device_name") ?: "Equipo"

        val retrofit = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this))
            .build()
        api = retrofit.create(KioskApi::class.java)
        gson = Gson()

        bindViews()
        setupActions()
        loadDeviceDetail()
        refreshAppsList()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
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
        btnRefreshApps = findViewById(R.id.btnRefreshApps)

        tvDeviceName.text = deviceName
        tvDeviceId.text = deviceId
        rvInstalledApps.layoutManager = LinearLayoutManager(this)
    }

    private fun setupActions() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnInstallFromStorage).setOnClickListener {
            apkPicker.launch("application/vnd.android.package-archive")
        }
        findViewById<Button>(R.id.btnRefreshApps).setOnClickListener { refreshAppsList() }
        findViewById<Button>(R.id.btnDeviceInfo).setOnClickListener { sendTask("device_info", "") }
        findViewById<Button>(R.id.btnReboot).setOnClickListener {
            confirmAction("¿Reiniciar el equipo?") { sendTask("reboot", "") }
        }
        findViewById<Button>(R.id.btnDeleteDevice).setOnClickListener {
            confirmAction("¿Eliminar este equipo de la lista?") { deleteDevice() }
        }
    }

    // ====================== CARGA INICIAL ======================

    private fun loadDeviceDetail() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.getDeviceDetail(deviceId) }
                if (resp.isSuccessful) {
                    val detail = resp.body()
                    detail?.device?.let { d ->
                        tvDeviceName.text = d.name
                        updateStatusUI(d.status)
                        tvLastSeen.text = "Última conexión: ${d.last_seen ?: "nunca"}"
                        detail?.installed_apps?.let { showApps(it) }
                    }
                }
            } catch (e: Exception) {
                toast("Error al cargar detalle: ${e.message}")
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

    // ====================== INSTALAR APK (subida real a Storage) ======================

    private fun uploadAndInstall(uri: Uri) {
        setTaskStatus("Copiando APK...", true)
        scope.launch {
            try {
                // 1. Copiar el stream del URI a un archivo temporal
                val tempFile = File(cacheDir, "upload_${System.currentTimeMillis()}.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                // 2. Subir a Supabase Storage vía backend (multipart)
                setTaskStatus("Subiendo APK al almacenamiento en la nube...", true)
                val requestFile = tempFile.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData(
                    "file", tempFile.name, requestFile
                )
                val bucketPart = "apks".toRequestBody("text/plain".toMediaTypeOrNull())

                val uploadResp = withContext(Dispatchers.IO) { api.uploadFile(part, bucketPart) }
                if (!uploadResp.isSuccessful) {
                    setTaskStatus("Error al subir: HTTP ${uploadResp.code()}", false)
                    return@launch
                }
                val uploaded = uploadResp.body()
                val apkUrl = uploaded?.url ?: run {
                    setTaskStatus("No se obtuvo URL del APK", false)
                    return@launch
                }

                // 3. Enviar comando de instalación al equipo
                setTaskStatus("Enviando comando de instalación al equipo...", true)
                sendTaskAndTrack("install", apkUrl, "Instalando APK en $deviceName...")

                // 4. Liberar archivo temporal
                tempFile.delete()
            } catch (e: Exception) {
                setTaskStatus("Error: ${e.message}", false)
            }
        }
    }

    // ====================== COMANDOS AL AGENTE ======================

    private fun sendTask(type: String, payload: String) {
        setTaskStatus("Enviando comando $type...", true)
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = type, payload = payload))
                }
                if (resp.isSuccessful && resp.body() != null) {
                    val task = resp.body()!!
                    trackTask(task.id, "Comando $type enviado")
                } else {
                    setTaskStatus("Error al enviar tarea: HTTP ${resp.code()}", false)
                }
            } catch (e: Exception) {
                setTaskStatus("Error: ${e.message}", false)
            }
        }
    }

    private fun sendTaskAndTrack(type: String, payload: String, initialMsg: String) {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = type, payload = payload))
                }
                if (resp.isSuccessful && resp.body() != null) {
                    trackTask(resp.body()!!.id, initialMsg)
                } else {
                    setTaskStatus("Error al enviar tarea: HTTP ${resp.code()}", false)
                }
            } catch (e: Exception) {
                setTaskStatus("Error: ${e.message}", false)
            }
        }
    }

    /** Hace polling del estado de una tarea hasta completarse o fallar. */
    private fun trackTask(taskId: String, initialMsg: String) {
        scope.launch {
            setTaskStatus(initialMsg, true)
            var attempts = 0
            var lastStatus = ""
            while (attempts < 90) { // hasta ~3 minutos
                delay(2000)
                attempts++
                try {
                    val resp = withContext(Dispatchers.IO) { api.getTask(taskId) }
                    if (resp.isSuccessful) {
                        val task = resp.body()?.task
                        if (task != null) {
                            if (task.status != lastStatus) {
                                lastStatus = task.status
                                setTaskStatus("Estado: ${task.status}", task.status != "completed" && task.status != "failed")
                            }
                            if (task.status == "completed" || task.status == "failed") {
                                val resultText = parseResult(task)
                                setTaskStatus(
                                    if (task.status == "completed") "✔ Completado" else "✘ Falló",
                                    false
                                )
                                tvTaskResult.text = resultText + if (task.error != null) "\nError: ${task.error}" else ""
                                if (task.type == "device_info" && task.status == "completed") renderDeviceResources(task.result)
                                if (task.type == "install" && task.status == "completed") {
                                    // Refrescar apps tras instalación
                                    delay(1500)
                                    refreshAppsList()
                                }
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Reintentar en el siguiente ciclo
                }
            }
            setTaskStatus("Tiempo de espera agotado", false)
        }
    }

    private fun parseResult(task: Task): String {
        return try {
            val result = task.result ?: return "(sin resultado)"
            // El agente guarda JSON (objeto, array o string entre comillas)
            if (result.trim().startsWith("[")) {
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val list: List<Map<String, Any>> = gson.fromJson(result, type)
                "Resultado: ${list.size} elementos\n" + list.take(20).joinToString("\n") { it.toString() }
            } else if (result.trim().startsWith("{")) {
                "Resultado:\n$result"
            } else {
                gson.fromJson(result, String::class.java)
            }
        } catch (e: Exception) {
            task.result ?: "(sin resultado)"
        }
    }


    private fun renderDeviceResources(raw: String?) {
        val target = findViewById<TextView>(R.id.tvDeviceResources)
        try {
            val data = JSONObject(raw ?: "{}")
            val totalRam = data.optLong("totalRamBytes")
            val freeRam = data.optLong("availRamBytes")
            val totalStorage = data.optLong("totalStorageBytes")
            val freeStorage = data.optLong("availStorageBytes")
            target.text = "RAM: ${formatBytes(freeRam)} libres de ${formatBytes(totalRam)}\\n" +
                "Almacenamiento: ${formatBytes(freeStorage)} libres de ${formatBytes(totalStorage)}\\n" +
                "Modelo: ${data.optString("manufacturer")} ${data.optString("model")} · Android ${data.optString("androidVersion")}"
        } catch (e: Exception) {
            target.text = "No se pudo interpretar la información del equipo: ${e.message}"
        }
    }

    private fun formatBytes(value: Long): String {
        val gb = value / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(java.util.Locale.US, "%.2f GB", gb)
        else String.format(java.util.Locale.US, "%.0f MB", value / (1024.0 * 1024.0))
    }

    // ====================== APPS INSTALADAS ======================

    private fun refreshAppsList() {
        setTaskStatus("Solicitando lista de apps al equipo...", true)
        scope.launch {
            try {
                // Enviar comando list_apps y esperar resultado
                val createResp = withContext(Dispatchers.IO) {
                    api.createTask(TaskRequest(device_id = deviceId, type = "list_apps", payload = ""))
                }
                if (!createResp.isSuccessful || createResp.body() == null) {
                    setTaskStatus("No se pudo enviar la solicitud", false)
                    return@launch
                }
                val taskId = createResp.body()!!.id

                // Esperar a que el agente reporte
                var attempts = 0
                var done = false
                while (attempts < 30 && !done) { // ~1 min
                    delay(2000)
                    attempts++
                    val t = withContext(Dispatchers.IO) { api.getTask(taskId) }
                    if (t.isSuccessful) {
                        val status = t.body()?.task?.status
                        if (status == "completed") { done = true }
                        if (status == "failed") {
                            setTaskStatus("El equipo reportó error listando apps", false)
                            return@launch
                        }
                    }
                }

                // Leer apps del endpoint dedicado
                val appsResp = withContext(Dispatchers.IO) { api.getInstalledApps(deviceId) }
                if (appsResp.isSuccessful) {
                    val apps = appsResp.body()?.apps ?: emptyList()
                    showApps(apps)
                    setTaskStatus("Lista de apps actualizada (${apps.size} apps)", false)
                } else {
                    setTaskStatus("No se pudo obtener la lista de apps", false)
                }
            } catch (e: Exception) {
                setTaskStatus("Error: ${e.message}", false)
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
        val options = arrayOf("Bloquear por 15 minutos", "Bloquear por 30 minutos", "Bloquear por 1 hora", "Bloquear por 2 horas", "Bloquear por 4 horas", "Bloquear hasta desactivar desde Control", "Desbloquear")
        android.app.AlertDialog.Builder(this)
            .setTitle("Control parental · ${app.appName}")
            .setItems(options) { _, which ->
                if (which == 6) showParentalPinDialog(app, false, 0)
                else {
                    val minutes = when (which) {
                        0 -> 15; 1 -> 30; 2 -> 60; 3 -> 120; 4 -> 240; else -> 0
                    }
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
        android.app.AlertDialog.Builder(this)
            .setTitle(if (blocking) "Definir PIN y bloquear" else "Desbloquear aplicación")
            .setMessage(if (blocking) "La app quedará oculta en el equipo. Para desbloquear antes de tiempo se solicitará este PIN." else "Introduce el PIN parental para retirar el bloqueo.")
            .setView(container)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(if (blocking) "Bloquear" else "Desbloquear") { _, _ ->
                val value = pin.text.toString()
                if (!value.matches(Regex("^\\d{4,8}$"))) {
                    toast("El PIN debe tener de 4 a 8 dígitos")
                    return@setPositiveButton
                }
                val payload = gson.toJson(mapOf(
                    "packageName" to app.packageName,
                    "pin" to value,
                    "durationMinutes" to minutes
                ))
                sendTask(if (blocking) "block_app" else "unblock_app", payload)
            }.show()
    }

    // ====================== UTILIDADES ======================

    private fun setTaskStatus(msg: String, inProgress: Boolean) {
        tvTaskStatus.text = msg
        progressTask.visibility = if (inProgress) View.VISIBLE else View.GONE
    }

    private fun confirmAction(message: String, onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Confirmar") { _, _ -> onConfirm() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteDevice() {
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { api.deleteDevice(deviceId) }
                if (resp.isSuccessful) {
                    toast("Equipo eliminado de la lista")
                    finish()
                } else {
                    toast("Error al eliminar: HTTP ${resp.code()}")
                }
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
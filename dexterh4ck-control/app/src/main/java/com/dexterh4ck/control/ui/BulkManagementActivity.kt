package com.dexterh4ck.control.ui

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.ApiConfig
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.KioskApi
import com.dexterh4ck.control.data.TaskRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

/** Gestión masiva: instalación con seguimiento por equipo y borrado del APK temporal. */
class BulkManagementActivity : AppCompatActivity() {
    private lateinit var api: KioskApi
    private lateinit var ring: ProgressBar
    private lateinit var percent: TextView
    private lateinit var stage: TextView
    private lateinit var progressFrame: FrameLayout

    private val apkPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) uploadAndInstallToAll(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_management)
        api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this)).build().create(KioskApi::class.java)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnBulkInstall).setOnClickListener {
            apkPicker.launch("application/vnd.android.package-archive")
        }
        findViewById<Button>(R.id.btnBulkUninstall).setOnClickListener { askPackageNameForBulkUninstall() }
        addProgressRing()
    }

    private fun addProgressRing() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        progressFrame = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(138))
        }
        ring = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            progressDrawable = getDrawable(R.drawable.circular_progress_ring)
            layoutParams = FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER)
        }
        percent = TextView(this).apply {
            text = "0%"
            textSize = 23f
            setTextColor(Color.rgb(8, 75, 170))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        progressFrame.addView(ring)
        progressFrame.addView(percent)
        stage = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }
        root.addView(progressFrame, 1)
        root.addView(stage, 2)
    }

    private fun showProgress(value: Int, message: String) {
        progressFrame.visibility = View.VISIBLE
        stage.visibility = View.VISIBLE
        ring.progress = value.coerceIn(0, 100)
        percent.text = "${value.coerceIn(0, 100)}%"
        stage.text = message
    }

    private fun uploadAndInstallToAll(uri: Uri) {
        lifecycleScope.launch {
            val tempFile = File(cacheDir, "bulk_${System.currentTimeMillis()}.apk")
            var cloudPath: String? = null
            var cloudDeleted = false
            var tasksCreated = false
            try {
                showProgress(0, "Leyendo APK del teléfono…")
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("No se pudo abrir el APK seleccionado")
                    input.use { source -> FileOutputStream(tempFile).use { output -> source.copyTo(output) } }
                }
                require(tempFile.isFile && tempFile.length() > 0L) { "El APK está vacío o no se puede leer" }
                require(tempFile.length() <= 512L * 1024L * 1024L) { "El APK supera el límite de 512 MB" }

                showProgress(0, "Subiendo APK temporal a la nube…")
                val media = "application/vnd.android.package-archive".toMediaTypeOrNull()
                val body = object : RequestBody() {
                    override fun contentType() = media
                    override fun contentLength() = tempFile.length()
                    override fun writeTo(sink: BufferedSink) {
                        tempFile.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var sent = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                sent += count
                                val value = ((sent * 100L) / tempFile.length().coerceAtLeast(1L)).toInt()
                                runOnUiThread { showProgress(value, "Subiendo APK a la nube…") }
                            }
                        }
                    }
                }
                val part = MultipartBody.Part.createFormData("file", tempFile.name, body)
                val bucket = "apks".toRequestBody("text/plain".toMediaTypeOrNull())
                val upload = withContext(Dispatchers.IO) { api.uploadFile(part, bucket) }
                if (!upload.isSuccessful) throw IllegalStateException("Error al subir APK: HTTP ${upload.code()}")
                val uploaded = upload.body() ?: throw IllegalStateException("El servidor no devolvió la ruta temporal")
                cloudPath = uploaded.path

                showProgress(0, "Enviando APK a los equipos…")
                val devicesResponse = withContext(Dispatchers.IO) { api.getDevices() }
                if (!devicesResponse.isSuccessful) throw IllegalStateException("No se pudieron cargar los equipos")
                val devices = devicesResponse.body().orEmpty()
                if (devices.isEmpty()) throw IllegalStateException("No hay equipos vinculados")

                val taskIds = mutableListOf<String>()
                var sendFailures = 0
                for (device in devices) {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            api.createTask(TaskRequest(device_id = device.device_id, type = "install", payload = uploaded.url))
                        }
                        if (response.isSuccessful && response.body() != null) taskIds.add(response.body()!!.id)
                        else sendFailures++
                    } catch (_: Exception) { sendFailures++ }
                }
                if (taskIds.isEmpty()) throw IllegalStateException("No se pudo enviar la instalación a ningún equipo")
                tasksCreated = true

                val downloaded = mutableSetOf<String>()
                val terminal = mutableSetOf<String>()
                val progressByTask = mutableMapOf<String, Int>()
                var attempts = 0
                while (terminal.size < taskIds.size && attempts < 86400) {
                    attempts++
                    for (taskId in taskIds) {
                        if (taskId in terminal) continue
                        try {
                            val response = withContext(Dispatchers.IO) { api.getTask(taskId) }
                            val task = response.body()?.task ?: continue
                            val info = runCatching {
                                val json = JSONObject(task.result ?: "{}")
                                json.optInt("progress", 0) to json.optString("phase")
                            }.getOrDefault(0 to "")
                            progressByTask[taskId] = info.first.coerceIn(0, 100)
                            if (info.second == "downloaded" || task.status in listOf("completed", "done", "failed", "expired")) {
                                downloaded.add(taskId)
                                progressByTask[taskId] = 100
                            }
                            if (task.status in listOf("completed", "done", "failed", "expired")) terminal.add(taskId)
                        } catch (_: Exception) { }
                    }
                    if (!cloudDeleted && downloaded.size == taskIds.size) {
                        val removed = withContext(Dispatchers.IO) { api.deleteFile(path = cloudPath!!) }
                        cloudDeleted = removed.isSuccessful
                    }
                    val aggregate = if (taskIds.isEmpty()) 0 else (taskIds.sumOf { progressByTask[it] ?: 0 } / taskIds.size)
                    val label = if (downloaded.size == taskIds.size)
                        "Todos los equipos descargaron el APK. La copia temporal en nube se elimina; esperando resultado de instalación…"
                    else "Descarga en equipos: ${downloaded.size}/${taskIds.size}. Enviado a ${taskIds.size} equipo(s)."
                    showProgress(aggregate, label)
                    delay(2000)
                }
                if (!cloudDeleted && cloudPath != null) {
                    val removed = withContext(Dispatchers.IO) { api.deleteFile(path = cloudPath!!) }
                    cloudDeleted = removed.isSuccessful
                }
                val resolvedCount = terminal.size
                val pendingCount = taskIds.size - resolvedCount
                val finalProgress = if (pendingCount == 0) 100 else taskIds.sumOf { progressByTask[it] ?: 0 } / taskIds.size
                showProgress(finalProgress, "Tareas resueltas: $resolvedCount de ${taskIds.size}. Pendientes: $pendingCount. Fallos al enviar: $sendFailures. APK temporal eliminado de la nube: $cloudDeleted.")
                toast("Proceso masivo: $resolvedCount resueltas; $pendingCount pendientes.")
            } catch (e: Exception) {
                showProgress(ring.progress, "Error: ${e.message}")
                toast("Error: ${e.message}")
                // No eliminar el archivo si ya hay equipos con tareas pendientes de descarga.
                if (cloudPath != null && !cloudDeleted && !tasksCreated) {
                    runCatching { withContext(Dispatchers.IO) { api.deleteFile(path = cloudPath!!) } }
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun askPackageNameForBulkUninstall() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "com.ejemplo.app"
        }
        AlertDialog.Builder(this)
            .setTitle("Desinstalar de TODOS los equipos")
            .setMessage("Escribe el nombre del paquete a desinstalar:")
            .setView(input)
            .setPositiveButton("Desinstalar en todos") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) bulkUninstall(pkg) else toast("Paquete vacío")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun bulkUninstall(packageName: String) {
        lifecycleScope.launch {
            try {
                val devicesResponse = withContext(Dispatchers.IO) { api.getDevices() }
                val devices = devicesResponse.body().orEmpty()
                if (devices.isEmpty()) { toast("No hay equipos registrados"); return@launch }
                var sent = 0
                var failed = 0
                for (device in devices) {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            api.createTask(TaskRequest(device_id = device.device_id, type = "uninstall", payload = packageName))
                        }
                        if (response.isSuccessful) sent++ else failed++
                    } catch (_: Exception) { failed++ }
                }
                toast("Desinstalación enviada: $sent equipos; $failed fallos")
            } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

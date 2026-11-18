package com.dexterh4ck.control.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.ApiConfig
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.KioskApi
import com.dexterh4ck.control.data.TaskRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Gestión masiva: instala un APK o desinstala un paquete en TODOS los equipos a la vez.
 */
class BulkManagementActivity : AppCompatActivity() {

    private lateinit var api: KioskApi

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadAndInstallToAll(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_management)

        val retrofit = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this))
            .build()
        api = retrofit.create(KioskApi::class.java)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnBulkInstall).setOnClickListener {
            apkPicker.launch("application/vnd.android.package-archive")
        }
        findViewById<Button>(R.id.btnBulkUninstall).setOnClickListener {
            askPackageNameForBulkUninstall()
        }
    }

    /**
     * Sube el APK una sola vez a Supabase Storage y luego envía
     * la tarea de instalación a todos los equipos con la URL pública.
     */
    private fun uploadAndInstallToAll(uri: Uri) {
        lifecycleScope.launch {
            try {
                toast("Copiando APK...")
                val tempFile = File(cacheDir, "bulk_${System.currentTimeMillis()}.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                toast("Subiendo APK a la nube (Supabase Storage)...")
                val requestFile = tempFile.asRequestBody("application/vnd.android.package-archive".toMediaTypeOrNull())
                val part = MultipartBody.Part.createFormData("file", tempFile.name, requestFile)
                val bucketPart = "apks".toRequestBody("text/plain".toMediaTypeOrNull())

                val uploadResp = withContext(Dispatchers.IO) { api.uploadFile(part, bucketPart) }
                if (!uploadResp.isSuccessful) {
                    toast("Error al subir: HTTP ${uploadResp.code()}")
                    return@launch
                }
                val apkUrl = uploadResp.body()?.url ?: run {
                    toast("No se obtuvo la URL del APK")
                    return@launch
                }
                toast("APK subido. Enviando a todos los equipos...")

                val devicesResp = withContext(Dispatchers.IO) { api.getDevices() }
                if (!devicesResp.isSuccessful) {
                    toast("No se pudieron listar los equipos")
                    return@launch
                }
                val devices = devicesResp.body() ?: emptyList()
                if (devices.isEmpty()) {
                    toast("No hay equipos registrados")
                    return@launch
                }

                var sent = 0
                var failed = 0
                for (device in devices) {
                    try {
                        val taskResp = withContext(Dispatchers.IO) {
                            api.createTask(
                                TaskRequest(
                                    device_id = device.device_id,
                                    type = "install",
                                    payload = apkUrl
                                )
                            )
                        }
                        if (taskResp.isSuccessful) sent++ else failed++
                    } catch (e: Exception) {
                        failed++
                    }
                }

                toast("Comando de instalación enviado a $sent equipos ($failed fallos)")
                tempFile.delete()
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun askPackageNameForBulkUninstall() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "com.ejemplo.app"
        AlertDialog.Builder(this)
            .setTitle("Desinstalar de TODOS los equipos")
            .setMessage("Escribe el nombre del paquete a desinstalar:")
            .setView(input)
            .setPositiveButton("Desinstalar en todos") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) bulkUninstall(pkg) else toast("Paquete vacío")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun bulkUninstall(packageName: String) {
        lifecycleScope.launch {
            try {
                val devicesResp = withContext(Dispatchers.IO) { api.getDevices() }
                val devices = devicesResp.body() ?: emptyList()
                if (devices.isEmpty()) {
                    toast("No hay equipos registrados")
                    return@launch
                }
                var sent = 0
                for (device in devices) {
                    try {
                        withContext(Dispatchers.IO) {
                            api.createTask(
                                TaskRequest(
                                    device_id = device.device_id,
                                    type = "uninstall",
                                    payload = packageName
                                )
                            )
                        }
                        sent++
                    } catch (_: Exception) {}
                }
                toast("Desinstalación de $packageName enviada a $sent equipos")
            } catch (e: Exception) {
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
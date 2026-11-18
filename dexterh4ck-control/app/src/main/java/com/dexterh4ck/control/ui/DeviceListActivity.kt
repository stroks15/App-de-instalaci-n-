package com.dexterh4ck.control.ui

import android.content.Intent
import android.app.AlertDialog
import android.widget.EditText
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.control.R
import com.dexterh4ck.control.adapter.DeviceAdapter
import com.dexterh4ck.control.data.ApiConfig
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.PairDeviceRequest
import com.dexterh4ck.control.data.KioskApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Pantalla principal de Dexterh4ck Control: lista todos los equipos conectados. */
class DeviceListActivity : AppCompatActivity() {
    private lateinit var api: KioskApi
    private lateinit var adapter: DeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        val retrofit = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this)).build()
        api = retrofit.create(KioskApi::class.java)

        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvDevices.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(emptyList()) { device ->
            if (device.device_id.isBlank()) {
                Toast.makeText(this, "El equipo no tiene un identificador válido", Toast.LENGTH_LONG).show()
            } else {
                startActivity(Intent(this, DeviceDetailActivity::class.java).apply {
                    putExtra("device_id", device.device_id)
                    putExtra("device_name", device.name)
                })
            }
        }
        rvDevices.adapter = adapter
        findViewById<Button>(R.id.btnPair).setOnClickListener { showPairDialog() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showActionsMenu(it) }
        loadDevices()
        startAutoRefresh()
    }

    private fun showActionsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Actualizar lista").setOnMenuItemClickListener { loadDevices(); true }
            menu.add("Gestión masiva").setOnMenuItemClickListener {
                startActivity(Intent(this@DeviceListActivity, BulkManagementActivity::class.java)); true
            }
            menu.add("Instalar APK desde GitHub").setOnMenuItemClickListener {
                startActivity(Intent(this@DeviceListActivity, GithubApkActivity::class.java)); true
            }
            show()
        }
    }

    private fun showPairDialog() {
        val input = EditText(this).apply {
            hint = "Ej. A7K9P2QX"
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this).setTitle("Vincular equipo")
            .setMessage("Escribe el código que aparece en Dexterh4ck Equipos.").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Vincular") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (!code.matches(Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$"))) {
                    Toast.makeText(this, "Ingresa los 8 caracteres del código (letras y números).", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        val response = withContext(Dispatchers.IO) { api.pairDevice(PairDeviceRequest(code)) }
                        if (response.isSuccessful) {
                            Toast.makeText(this@DeviceListActivity, "Equipo vinculado correctamente", Toast.LENGTH_SHORT).show()
                            loadDevices()
                        } else {
                            val detail = response.errorBody()?.string()?.take(180).orEmpty()
                            Toast.makeText(this@DeviceListActivity, "No se pudo vincular (HTTP ${response.code()}): $detail", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@DeviceListActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private fun startAutoRefresh() {
        lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                loadDevices(quiet = true)
            }
        }
    }

    private fun loadDevices(quiet: Boolean = false) {
        if (!quiet) progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { api.getDevices() }
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    val devices = response.body() ?: emptyList()
                    adapter.updateDevices(devices)
                    tvEmpty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
                    if (devices.isEmpty() && !quiet) {
                        tvEmpty.text = "No hay equipos. Instala Dexterh4ck Equipos en tu TV Box / Fire Stick y configúralo."
                    }
                } else if (!quiet) {
                    Toast.makeText(this@DeviceListActivity, "Error al cargar equipos (HTTP ${response.code()})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                if (!quiet) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Error de conexión: ${e.message}"
                }
            }
        }
    }
}

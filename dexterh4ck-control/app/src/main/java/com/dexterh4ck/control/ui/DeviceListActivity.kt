package com.dexterh4ck.control.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import com.dexterh4ck.control.data.Device
import com.dexterh4ck.control.data.KioskApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Pantalla principal de Dexterh4ck Control: lista todos los equipos conectados.
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var api: KioskApi
    private lateinit var adapter: DeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        val retrofit = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        api = retrofit.create(KioskApi::class.java)

        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvDevices.layoutManager = LinearLayoutManager(this)

        adapter = DeviceAdapter(emptyList()) { device ->
            val intent = Intent(this, DeviceDetailActivity::class.java)
            intent.putExtra("device_id", device.device_id)
            intent.putExtra("device_name", device.name)
            startActivity(intent)
        }
        rvDevices.adapter = adapter

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadDevices() }
        findViewById<Button>(R.id.btnBulk).setOnClickListener {
            startActivity(Intent(this, BulkManagementActivity::class.java))
        }

        loadDevices()
        startAutoRefresh()
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
                    Toast.makeText(this@DeviceListActivity, "Error al cargar equipos", Toast.LENGTH_SHORT).show()
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
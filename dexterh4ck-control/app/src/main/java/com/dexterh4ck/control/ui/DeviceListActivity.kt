package com.dexterh4ck.control.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
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
import com.dexterh4ck.control.data.adb.NativeAdbClient
import com.dexterh4ck.control.data.netbird.NetBirdManager
import com.dexterh4ck.control.data.KioskApi
import com.dexterh4ck.control.data.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Control device list. Device ownership is established by Supabase Auth, not pairing codes. */
class DeviceListActivity : AppCompatActivity() {
    private lateinit var api: KioskApi
    private lateinit var adapter: DeviceAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_list)

        api = Retrofit.Builder().baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this)).build().create(KioskApi::class.java)

        val rvDevices = findViewById<RecyclerView>(R.id.rvDevices)
        progressBar = findViewById(R.id.progressBar)
        tvEmpty = findViewById(R.id.tvEmpty)
        rvDevices.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(
            emptyList(),
            onItemClick = { device ->
                if (device.device_id.isBlank()) {
                    Toast.makeText(this, "El equipo no tiene un identificador válido", Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, DeviceDetailActivity::class.java).apply {
                        putExtra("device_id", device.device_id)
                        putExtra("device_name", device.name)
                    })
                }
            },
            onReconnect = { device -> reconnectDevice(device) }
        )
        rvDevices.adapter = adapter
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showActionsMenu(it) }
        loadDevices()
        startAutoRefresh()
    }

    override fun onStart() {
        super.onStart()
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { loadDevices(quiet = true) }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(networkCallback!!) }
    }

    override fun onStop() {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { callback -> runCatching { manager.unregisterNetworkCallback(callback) } }
        networkCallback = null
        super.onStop()
    }

    private fun showActionsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Herramientas avanzadas · NetBird/ADB").setOnMenuItemClickListener {\n                startActivity(Intent(this@DeviceListActivity, NetBirdToolsActivity::class.java)); true\n            }\n            menu.add("Actualizar lista").setOnMenuItemClickListener { loadDevices(); true }
            menu.add("Gestión masiva").setOnMenuItemClickListener {
                startActivity(Intent(this@DeviceListActivity, BulkManagementActivity::class.java)); true
            }
            menu.add("Instalar APK desde GitHub").setOnMenuItemClickListener {
                startActivity(Intent(this@DeviceListActivity, GithubApkActivity::class.java)); true
            }
            show()
        }
    }

    private fun reconnectDevice(device: Device) {
        val status = NetBirdManager.status(this)
        if (!status.connected) {
            val opened = NetBirdManager.openOfficialClient(this)
            Toast.makeText(
                this,
                if (opened) "Abre NetBird y confirma que el controlador esté conectado." else "NetBird no está instalado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val ip = device.assigned_ip?.substringBefore('/')?.takeIf {
            it.matches(Regex("100\\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)"))
        }
        if (ip == null) {
            Toast.makeText(this, "Este equipo aún no tiene una IP NetBird 100.64.0.0/10 guardada. Registra su IP desde Herramientas avanzadas.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Comprobando ADB NetBird de ${device.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) { NativeAdbClient(this@DeviceListActivity).pingPort(ip) }
            Toast.makeText(
                this@DeviceListActivity,
                if (reachable) "ADB disponible en $ip:5555. La autorización RSA se comprobará al ejecutar un comando."
                else "No se alcanza $ip:5555. Verifica NetBird, ADB TCP y la autorización en el equipo.",
                Toast.LENGTH_LONG
            ).show()
        }
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
                        tvEmpty.text = "No hay equipos registrados en esta cuenta. Inicia sesión en Dexterh4ck Equipos con el mismo correo."
                    }
                } else if (!quiet) {
                    Toast.makeText(this@DeviceListActivity, "Error al cargar equipos: HTTP ${response.code()}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                if (!quiet) Toast.makeText(this@DeviceListActivity, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}

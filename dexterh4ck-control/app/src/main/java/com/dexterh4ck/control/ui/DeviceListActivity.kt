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
import com.dexterh4ck.control.data.ControlVpnBootstrap
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
        Toast.makeText(this, "Restableciendo WireGuard y verificando ${device.name}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ControlVpnBootstrap.reconnect(this@DeviceListActivity)
                }
                val vpnIp = device.assigned_ip?.substringBefore('/')
                    ?: throw IllegalStateException("El equipo todavía no tiene una IP VPN asignada.")
                withContext(Dispatchers.IO) { checkVpnHealth(vpnIp) }
                val response = withContext(Dispatchers.IO) {
                    api.getDeviceDetail(device.device_id)
                }
                if (response.isSuccessful) {
                    val current = response.body()
                    val vpnIp = device.assigned_ip?.substringBefore('/') ?: "IP no asignada"
                    val state = current?.device?.status ?: device.status
                    Toast.makeText(
                        this@DeviceListActivity,
                        "VPN renovada · $vpnIp · Agente: $state",
                        Toast.LENGTH_LONG
                    ).show()
                    loadDevices(quiet = true)
                } else {
                    Toast.makeText(
                        this@DeviceListActivity,
                        "VPN renovada, pero el agente no respondió (HTTP ${response.code()}).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                Toast.makeText(
                    this@DeviceListActivity,
                    "No se pudo reconectar: ${error.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkVpnHealth(ip: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 8787), 3500)
            socket.soTimeout = 3500
            val request = "GET /health HTTP/1.1\r\nHost: $ip\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII)).readLine().orEmpty()
            if (!statusLine.contains("200")) {
                throw IllegalStateException("El endpoint VPN del equipo no respondió correctamente.")
            }
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

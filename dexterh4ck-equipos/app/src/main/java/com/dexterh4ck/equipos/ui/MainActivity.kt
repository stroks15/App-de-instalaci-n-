package com.dexterh4ck.equipos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.app.ActivityManager
import android.app.Activity
import android.net.VpnService
import android.app.AlertDialog
import android.os.Environment
import android.os.StatFs
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dexterh4ck.equipos.R
import com.dexterh4ck.equipos.data.AgentConfig
import com.dexterh4ck.equipos.data.AgentTokenStore
import com.dexterh4ck.equipos.service.CommandService
import com.dexterh4ck.equipos.util.DeviceIdentity
import com.dexterh4ck.equipos.util.DevicePolicyHelper
import com.dexterh4ck.equipos.data.AgentAuthClient
import com.dexterh4ck.equipos.data.WireGuardKeyStore
import com.dexterh4ck.equipos.vpn.WireGuardTunnelManager
import com.dexterh4ck.equipos.vpn.VpnHealthServer
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Panel compacto y navegable con mando para Android TV / Fire TV. */
class MainActivity : AppCompatActivity() {
    companion object { private const val REQUEST_POST_NOTIFICATIONS = 7001 }

    private lateinit var serverUrl: EditText
    private lateinit var deviceName: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var adbStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var deviceNameHeader: TextView
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectConfiguredVpn()
        else connectionStatus.text = "Cuenta registrada; falta autorizar el permiso VPN de Android."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()
        serverUrl = findViewById(R.id.etServerUrl)
        deviceName = findViewById(R.id.etDeviceName)
        adbStatus = findViewById(R.id.tvAdbStatus)
        connectionStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        serverUrl.setText(prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL))
        deviceName.setText(prefs.getString(AgentConfig.KEY_DEVICE_NAME, "Equipo TV"))
        email = findViewById(R.id.etEmail)
        password = findViewById(R.id.etPassword)
        deviceNameHeader = findViewById(R.id.tvDeviceNameHeader)
        deviceNameHeader.text = prefs.getString(AgentConfig.KEY_DEVICE_NAME, "Equipo Android")
        if (!AgentTokenStore.getToken(this).isNullOrBlank()) hideLoginPanel()
        refreshAdbStatus()
        refreshResourceStats()
        refreshVpnStatus()
        refreshInstalledAppCount()
        val ownerStatus = DevicePolicyHelper.configureDedicatedDevice(this)
        if (DevicePolicyHelper.isDeviceOwner(this)) {
            connectionStatus.text = ownerStatus
        } else {
            connectionStatus.text = "Equipo listo. Para modo administrado y apps silenciosas, activa Device Owner por ADB."
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btnAdbSettings).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        findViewById<Button>(R.id.btnDeviceOwner).setOnClickListener { showDeviceOwnerSetup() }
        findViewById<Button>(R.id.btnOptimizeMemory).setOnClickListener { optimizeMemory() }
        refreshDeviceOwnerStatus()
    }

    private fun refreshDeviceOwnerStatus() {
        val status = findViewById<TextView>(R.id.tvDeviceOwnerStatus)
        val button = findViewById<Button>(R.id.btnDeviceOwner)
        if (DevicePolicyHelper.isDeviceOwner(this)) {
            val policyStatus = DevicePolicyHelper.configureDedicatedDevice(this)
            status.text = "Device Owner: ACTIVADO · $policyStatus"
            status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            button.text = "Device Owner activo · Ver estado"
        } else {
            status.text = "Device Owner: no activado · requiere configuración inicial por ADB"
            status.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            button.text = "Activar Device Owner"
        }
    }

    private fun showDeviceOwnerSetup() {
        if (DevicePolicyHelper.isDeviceOwner(this)) {
            val result = DevicePolicyHelper.configureDedicatedDevice(this)
            refreshDeviceOwnerStatus()
            AlertDialog.Builder(this).setTitle("Device Owner activo")
                .setMessage(result + "\n\nLa instalación silenciosa está habilitada cuando Android y el fabricante permiten PackageInstaller.")
                .setPositiveButton("Aceptar", null).show()
            return
        }
        val command = "adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        AlertDialog.Builder(this)
            .setTitle("Activar Device Owner")
            .setMessage(
                "Android no permite que una app se otorgue a sí misma el rol Device Owner. " +
                    "Conecta el TV Box / Fire Stick a una computadora con ADB, instala Dexterh4ck Equipos y ejecuta:\n\n" +
                    command + "\n\n" +
                    "IMPORTANTE: normalmente sólo se puede activar en un equipo recién restablecido de fábrica, antes de completar el aprovisionamiento y sin cuentas añadidas. " +
                    "Este botón no ejecuta el comando; sólo lo prepara para copiar."
            )
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Copiar comando") { _, _ ->
                clipboard.setPrimaryClip(ClipData.newPlainText("Comando Device Owner", command))
                Toast.makeText(this, "Comando ADB copiado", Toast.LENGTH_LONG).show()
            }
            .setPositiveButton("Abrir ajustes ADB") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
            .show()
    }


    private fun connectConfiguredVpn() {
        lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
                val ip = prefs.getString("assigned_vpn_ip", null)?.substringBefore('/').orEmpty()
                val endpoint = prefs.getString("vpn_endpoint", null).orEmpty()
                val serverKey = prefs.getString("vpn_server_public_key", null).orEmpty()
                require(ip.isNotBlank() && endpoint.isNotBlank() && serverKey.isNotBlank()) {
                    "La configuración del gateway WireGuard está incompleta."
                }
                val pair = WireGuardKeyStore.getOrCreate(this@MainActivity)
                val configuration = """
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
                withContext(Dispatchers.IO) {
                    WireGuardTunnelManager.connect(this@MainActivity, configuration)
                    VpnHealthServer.start(this@MainActivity, ip)
                }
                connectionStatus.text = "WireGuard conectado · $ip"
            } catch (error: Exception) {
                connectionStatus.text = "VPN no conectada: ${error.localizedMessage}"
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::adbStatus.isInitialized) { refreshAdbStatus(); refreshResourceStats(); refreshDeviceOwnerStatus(); refreshVpnStatus(); refreshInstalledAppCount(); ensureAgentServiceRunning() }
    }

    private fun hideLoginPanel() {
        findViewById<View>(R.id.loginPanel).visibility = View.GONE
    }

    private fun refreshVpnStatus() {
        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString("assigned_vpn_ip", null).orEmpty()
        val configured = prefs.getBoolean("vpn_enabled", false)
        val view = findViewById<TextView>(R.id.tvVpnStatus)
        view.text = when {
            configured && ip.isNotBlank() -> "Gateway provisionado · IP $ip"
            ip.isNotBlank() -> "IP reservada $ip · gateway pendiente de configurar"
            else -> "VPN: esperando registro de cuenta"
        }
        view.setTextColor(ContextCompat.getColor(this,
            if (configured) android.R.color.holo_green_dark else android.R.color.darker_gray))
    }

    private fun refreshInstalledAppCount() {
        val count = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA).size
        }.getOrDefault(0)
        findViewById<TextView>(R.id.tvAppCount).text = "Aplicaciones instaladas: $count"
    }

    private fun optimizeMemory() {
        // Android does not allow third-party apps to clear another app's private cache.
        onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        Runtime.getRuntime().gc()
        refreshResourceStats()
        Toast.makeText(this, "KioskGrid optimizó su propio proceso. Android administra la RAM de las demás apps.", Toast.LENGTH_LONG).show()
    }

    private fun refreshResourceStats() {
        val ramView = findViewById<TextView>(R.id.tvRamValue)
        val storageView = findViewById<TextView>(R.id.tvStorageValue)
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val ramTotal = memory.totalMem
        val ramFree = memory.availMem
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageTotal = stat.totalBytes
        val storageFree = stat.availableBytes
        ramView.text = "RAM: ${formatBytes(ramFree)} libres de ${formatBytes(ramTotal)}"
        storageView.text = "Almacenamiento: ${formatBytes(storageFree)} libres de ${formatBytes(storageTotal)}"
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) String.format(java.util.Locale.US, "%.2f GB", gib)
        else String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    }

    private fun refreshAdbStatus() {
        val enabled = runCatching { Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        adbStatus.text = if (enabled) "ADB: Activado" else "ADB: Desactivado — habilítalo si necesitas depuración"
        adbStatus.setTextColor(ContextCompat.getColor(this, if (enabled) android.R.color.holo_green_dark else android.R.color.darker_gray))
    }

    private fun ensureAgentServiceRunning() {
        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL).orEmpty()
        if (savedUrl.startsWith("https://") && prefs.getString(AgentConfig.KEY_DEVICE_ID, null) != null && !AgentTokenStore.getToken(this).isNullOrBlank()) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, CommandService::class.java)) }
        }
    }

    private fun saveAndStart() {
        val url = serverUrl.text.toString().trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "La URL del backend debe usar HTTPS", Toast.LENGTH_LONG).show()
            return
        }
        val name = deviceName.text.toString().trim().ifBlank { "Equipo TV" }
        val accountEmail = email.text.toString().trim()
        val accountPassword = password.text.toString()
        if (accountEmail.isBlank() || accountPassword.isBlank()) {
            Toast.makeText(this, "Ingresa el correo y la contraseña de tu cuenta KioskGrid.", Toast.LENGTH_LONG).show()
            return
        }
        val hardwareId = DeviceIdentity.getOrCreateDeviceId(this)
        val loginButton = findViewById<Button>(R.id.btnSave)
        loginButton.isEnabled = false
        connectionStatus.text = "Autenticando cuenta y registrando equipo…"
        lifecycleScope.launch {
            try {
                val registration = withContext(Dispatchers.IO) {
                    AgentAuthClient.loginAndRegister(
                        this@MainActivity, url, hardwareId, name,
                        accountEmail, accountPassword
                    )
                }
                password.text.clear()
                hideLoginPanel()
                deviceNameHeader.text = name
                refreshVpnStatus()
                ContextCompat.startForegroundService(
                    this@MainActivity,
                    Intent(this@MainActivity, CommandService::class.java)
                )
                val device = registration.optJSONObject("device")
                val vpn = registration.optJSONObject("vpn")
                val ip = device?.optString("assigned_ip").orEmpty()
                val vpnStatus = vpn?.optString("status") ?: "not_configured"
                connectionStatus.text = "Cuenta vinculada · IP reservada: ${ip.ifBlank { "pendiente" }} · VPN: $vpnStatus"
                if (vpn?.optBoolean("enabled") == true) {
                    val permission = VpnService.prepare(this@MainActivity)
                    if (permission != null) vpnPermissionLauncher.launch(permission)
                    else connectConfiguredVpn()
                }
                Toast.makeText(this@MainActivity, "Equipo registrado en tu cuenta KioskGrid.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                connectionStatus.text = "No se pudo iniciar sesión: ${error.localizedMessage}"
                Toast.makeText(this@MainActivity, error.localizedMessage ?: "Error de autenticación", Toast.LENGTH_LONG).show()
            } finally {
                loginButton.isEnabled = true
            }
        }
    }
}

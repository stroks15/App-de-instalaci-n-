package com.dexterh4ck.equipos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dexterh4ck.equipos.R
import com.dexterh4ck.equipos.data.AgentConfig
import com.dexterh4ck.equipos.service.CommandService
import com.dexterh4ck.equipos.util.DeviceIdentity
import com.dexterh4ck.equipos.util.PairingCode
import com.dexterh4ck.equipos.util.DevicePolicyHelper

/** Panel compacto y navegable con mando para Android TV / Fire TV. */
class MainActivity : AppCompatActivity() {
    companion object { private const val REQUEST_POST_NOTIFICATIONS = 7001 }

    private lateinit var serverUrl: EditText
    private lateinit var deviceName: EditText
    private lateinit var pairingCode: TextView
    private lateinit var adbStatus: TextView
    private lateinit var connectionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestNotificationPermissionIfNeeded()
        serverUrl = findViewById(R.id.etServerUrl)
        deviceName = findViewById(R.id.etDeviceName)
        pairingCode = findViewById(R.id.tvPairingCode)
        adbStatus = findViewById(R.id.tvAdbStatus)
        connectionStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        serverUrl.setText(prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL))
        deviceName.setText(prefs.getString(AgentConfig.KEY_DEVICE_NAME, "Equipo TV"))
        pairingCode.text = PairingCode.getOrCreate(this)
        refreshAdbStatus()
        refreshResourceStats()
        val ownerStatus = DevicePolicyHelper.configureDedicatedDevice(this)
        if (DevicePolicyHelper.isDeviceOwner(this)) {
            connectionStatus.text = ownerStatus
        } else {
            connectionStatus.text = "Equipo listo. Para modo administrado y apps silenciosas, activa Device Owner por ADB."
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveAndStart() }
        findViewById<Button>(R.id.btnCopyCode).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Código de vinculación", pairingCode.text))
            Toast.makeText(this, "Código copiado", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnAdbSettings).setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
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
        if (::adbStatus.isInitialized) { refreshAdbStatus(); refreshResourceStats() }
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
    }

    private fun saveAndStart() {
        val url = serverUrl.text.toString().trim().trimEnd('/')
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "La URL del backend debe usar HTTPS", Toast.LENGTH_LONG).show()
            return
        }
        val name = deviceName.text.toString().trim().ifBlank { "Equipo TV" }
        val hardwareId = DeviceIdentity.getOrCreateDeviceId(this)
        getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(AgentConfig.KEY_SERVER_URL, url)
            .putString(AgentConfig.KEY_DEVICE_NAME, name)
            .putString(AgentConfig.KEY_DEVICE_ID, hardwareId)
            .apply()
        try {
            ContextCompat.startForegroundService(this, Intent(this, CommandService::class.java))
            connectionStatus.text = "Servicio iniciado — registrando equipo"
            Toast.makeText(this, "Agente iniciado. Comparte el código para vincularlo.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            connectionStatus.text = "No se pudo iniciar: ${error.localizedMessage}"
        }
    }
}

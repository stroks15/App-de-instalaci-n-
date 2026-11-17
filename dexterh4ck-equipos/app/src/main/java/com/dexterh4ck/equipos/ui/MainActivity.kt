package com.dexterh4ck.equipos.ui

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dexterh4ck.equipos.R
import com.dexterh4ck.equipos.data.AgentConfig
import com.dexterh4ck.equipos.service.CommandService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Pantalla principal de Dexterh4ck Equipos (agente).
 * Permite configurar la URL del servidor, el nombre del equipo,
 * ver información del dispositivo y las apps instaladas.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceName: EditText
    private lateinit var tvDeviceId: TextView
    private lateinit var tvStatus: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvLastCommand: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvAppsCount: TextView
    private lateinit var tvAppsList: TextView

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etServerUrl = findViewById(R.id.etServerUrl)
        etDeviceName = findViewById(R.id.etDeviceName)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvStatus = findViewById(R.id.tvStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvLastCommand = findViewById(R.id.tvLastCommand)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvAppsCount = findViewById(R.id.tvAppsCount)
        tvAppsList = findViewById(R.id.tvAppsList)

        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)

        // Cargar configuración guardada
        etServerUrl.setText(prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL) ?: AgentConfig.DEFAULT_SERVER_URL)
        etDeviceName.setText(prefs.getString(AgentConfig.KEY_DEVICE_NAME, "") ?: "")

        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, null)
            ?: Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString().replace("-", "").take(16)
        tvDeviceId.text = deviceId

        updateStatusUI(prefs.getBoolean(AgentConfig.KEY_IS_PAIRED, false))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            saveConfig()
        }

        findViewById<Button>(R.id.btnCopyId).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("device_id", deviceId))
            Toast.makeText(this, "ID copiado: $deviceId", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnReboot).setOnClickListener {
            confirmReboot()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopService(Intent(this, CommandService::class.java))
            prefs.edit().putBoolean(AgentConfig.KEY_IS_PAIRED, false).apply()
            updateStatusUI(false)
            Toast.makeText(this, "Servicio remoto detenido", Toast.LENGTH_SHORT).show()
        }

        showDeviceInfo()
        showInstalledApps()
    }

    private fun saveConfig() {
        val serverUrl = etServerUrl.text.toString().trim().trimEnd('/')
        val deviceName = etDeviceName.text.toString().trim().ifEmpty { "Equipo TV" }

        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(AgentConfig.KEY_SERVER_URL, serverUrl)
            .putString(AgentConfig.KEY_DEVICE_NAME, deviceName)
            .putString(AgentConfig.KEY_DEVICE_ID, tvDeviceId.text.toString())
            .putBoolean(AgentConfig.KEY_IS_PAIRED, true)
            .apply()

        // Notificación para el servicio en primer plano (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // (Re)iniciar el servicio de escucha de comandos
        val serviceIntent = Intent(this, CommandService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(this, "Configuración guardada. Servicio remoto iniciado.", Toast.LENGTH_LONG).show()
        updateStatusUI(true)
    }

    private fun updateStatusUI(paired: Boolean) {
        if (paired) {
            tvStatus.text = " Conectado (gestión remota activa)"
            viewStatusDot.setBackgroundColor(0xFF4CAF50.toInt())
        } else {
            tvStatus.text = " No emparejado"
            viewStatusDot.setBackgroundColor(0xFF9E9E9E.toInt())
        }
    }

    private fun showDeviceInfo() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalGb = stat.totalBytes / (1024 * 1024 * 1024)
        val availGb = stat.availableBytes / (1024 * 1024 * 1024)

        val ip = try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo
            String.format(
                "%d.%d.%d.%d",
                dhcp.ipAddress and 0xff, dhcp.ipAddress shr 8 and 0xff,
                dhcp.ipAddress shr 16 and 0xff, dhcp.ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) { "desconocida" }

        val uptimeMin = SystemClock.elapsedRealtime() / 60000

        tvDeviceInfo.text = buildString {
            append("Modelo: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("IP: $ip\n")
            append("RAM total: ${Formatter.formatFileSize(this@MainActivity, memInfo.totalMem)}\n")
            append("Almacenamiento: ${availGb}GB libres / ${totalGb}GB\n")
            append("Tiempo encendido: ${uptimeMin} min\n")
            append("Es Fire OS: ${Build.MANUFACTURER.contains("Amazon", ignoreCase = true)}")
        }
    }

    private fun showInstalledApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val userApps = apps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        tvAppsCount.text = "Apps instaladas: ${apps.size} (${userApps.size} del usuario)"
        tvAppsList.text = userApps.take(15).mapNotNull { it.loadLabel(pm)?.toString() }.joinToString("\n")
    }

    private fun confirmReboot() {
        try {
            val intent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN").apply {
                putExtra("android.intent.extra.KEY_CONFIRM", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el diálogo de reinicio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
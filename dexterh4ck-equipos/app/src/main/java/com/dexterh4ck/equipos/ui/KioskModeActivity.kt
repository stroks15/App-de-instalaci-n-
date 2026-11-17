package com.dexterh4ck.equipos.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dexterh4ck.equipos.R
import com.dexterh4ck.equipos.adapter.KioskAppAdapter
import com.dexterh4ck.equipos.data.AgentConfig
import com.dexterh4ck.equipos.data.DeviceRegistration
import com.dexterh4ck.equipos.data.KioskApi
import com.dexterh4ck.equipos.util.DevicePolicyHelper
import com.dexterh4ck.equipos.util.KioskManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de bloqueo (modo kiosk).
 * Pantalla completa inmersiva con la cuadrícula de apps permitidas.
 * Se activa remotamente desde Dexterh4ck Control (comando kiosk_enable)
 * o localmente desde la pantalla principal del agente.
 */
class KioskModeActivity : AppCompatActivity() {

    private lateinit var adapter: KioskAppAdapter
    private var kioskScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishKiosk(remote = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_kiosk_mode)
        hideSystemUI()

        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val deviceName = prefs.getString(AgentConfig.KEY_DEVICE_NAME, "Equipo") ?: "Equipo"
        findViewById<TextView>(R.id.tvKioskDeviceName).text = "$deviceName · uso restringido"

        val rv = findViewById<RecyclerView>(R.id.rvKioskApps)
        rv.layoutManager = GridLayoutManager(this, 5)
        adapter = KioskAppAdapter(loadAllowedApps()) { pkg -> launchApp(pkg) }
        rv.adapter = adapter

        findViewById<Button>(R.id.btnKioskExit).setOnClickListener { askPinToExit() }

        registerReceiver(stopReceiver, IntentFilter(KioskManager.ACTION_STOP_KIOSK))
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        DevicePolicyHelper.startLockTaskIfPermitted(this)
    }

    override fun onPause() {
        super.onPause()
        DevicePolicyHelper.stopLockTaskIfActive(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    override fun onBackPressed() {
        // En modo kiosk, Atrás no sale (solo con PIN)
        askPinToExit()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun loadAllowedApps(): List<Pair<String, String>> {
        val pm = packageManager
        val allowed = KioskManager.allowedPackages(this)
        val result = mutableListOf<Pair<String, String>>()

        if (allowed.isNotEmpty()) {
            allowed.forEach { pkg ->
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    result.add(pkg to pm.getApplicationLabel(info).toString())
                } catch (_: Exception) {}
            }
        } else {
            // Sin whitelist: todas las apps del usuario
            pm.getInstalledApplications(0).forEach { info ->
                if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                    result.add(info.packageName to (pm.getApplicationLabel(info)?.toString() ?: info.packageName))
                }
            }
        }
        return result.sortedBy { it.second.lowercase(Locale.US) }
    }

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, "No se puede abrir: $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askPinToExit() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle("Salir del modo kiosk")
            .setMessage("Ingresa el PIN de administrador:")
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                if (input.text.toString() == KioskManager.pin(this)) {
                    finishKiosk(remote = false)
                } else {
                    Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun finishKiosk(remote: Boolean) {
        KioskManager.disable(this)
        DevicePolicyHelper.stopLockTaskIfActive(this)

        // Reportar al backend que el kiosk se desactivó
        val prefs = getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val deviceId = prefs.getString(AgentConfig.KEY_DEVICE_ID, "") ?: ""
        val serverUrl = prefs.getString(AgentConfig.KEY_SERVER_URL, AgentConfig.DEFAULT_SERVER_URL)
            ?: AgentConfig.DEFAULT_SERVER_URL

        if (deviceId.isNotEmpty()) {
            kioskScope.launch {
                try {
                    val retrofit = Retrofit.Builder()
                        .baseUrl(serverUrl.trimEnd('/') + "/")
                        .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                        .build()
                    val api = retrofit.create(KioskApi::class.java)
                    api.updateDevice(
                        deviceId,
                        mapOf("kiosk_enabled" to false, "updated_at" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
                    )
                } catch (_: Exception) {}
            }
        }

        if (remote) {
            com.dexterh4ck.equipos.util.NotifyHelper.showKioskNotification(
                this, "Modo kiosk desactivado", "El administrador desbloqueó el equipo."
            )
        }
        finish()
    }
}
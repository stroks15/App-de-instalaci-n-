package com.dexterh4ck.equipos.util

import android.content.Context
import android.content.Intent

/**
 * Gestor del modo kiosk (bloqueo del equipo).
 * Estado persistido en SharedPreferences.
 */
object KioskManager {

    const val PREF = "dexterh4ck_kiosk"
    const val KEY_ENABLED = "kiosk_enabled"
    const val KEY_ALLOWED = "kiosk_allowed_csv"
    const val KEY_PIN = "kiosk_pin"
    const val DEFAULT_PIN = "1234"

    const val ACTION_STOP_KIOSK = "com.dexterh4ck.equipos.action.STOP_KIOSK"
    const val ACTION_KIOSK_STATE = "com.dexterh4ck.equipos.action.KIOSK_STATE"
    const val EXTRA_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun allowedPackages(context: Context): List<String> {
        val csv = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_ALLOWED, "") ?: ""
        return csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun pin(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun setPin(context: Context, newPin: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PIN, newPin).apply()
    }

    /**
     * Activa el modo kiosk. Guarda el whitelist y lanza la actividad de bloqueo.
     */
    fun enable(context: Context, allowedCsv: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_ALLOWED, allowedCsv)
            .apply()

        val intent = Intent(context, com.dexterh4ck.equipos.ui.KioskModeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        broadcastState(context, true)
    }

    /**
     * Desactiva el modo kiosk (local o por comando remoto).
     */
    fun disable(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false)
            .apply()

        // Avisar a la actividad de kiosk que se cierre
        context.sendBroadcast(Intent(ACTION_STOP_KIOSK))
        broadcastState(context, false)
    }

    private fun broadcastState(context: Context, enabled: Boolean) {
        context.sendBroadcast(Intent(ACTION_KIOSK_STATE).putExtra(EXTRA_ENABLED, enabled))
    }
}
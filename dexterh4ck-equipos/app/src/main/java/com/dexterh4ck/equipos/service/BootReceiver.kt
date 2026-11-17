package com.dexterh4ck.equipos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dexterh4ck.equipos.data.AgentConfig

/** Reactiva el agente tras arranque o actualización, si el usuario ya lo configuró. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AgentConfig.KEY_IS_PAIRED, false)) return
        Log.i("BootReceiver", "Reactivando agente tras ${intent.action}")
        val service = Intent(context, CommandService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, service)
            else context.startService(service)
        } catch (error: Exception) {
            Log.e("BootReceiver", "No se pudo iniciar el agente", error)
        }
    }
}

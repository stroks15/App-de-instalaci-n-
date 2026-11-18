package com.dexterh4ck.equipos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Acción explícita del usuario para aceptar una instalación pendiente. */
class PendingInstallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.dexterh4ck.equipos.action.ACCEPT_PENDING_INSTALL"
        const val EXTRA_TASK_ID = "pending_install_task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ACCEPT) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.takeIf { it.isNotBlank() } ?: return
        val service = Intent(context, CommandService::class.java).apply {
            action = ACTION_ACCEPT
            putExtra(EXTRA_TASK_ID, taskId)
        }
        ContextCompat.startForegroundService(context, service)
    }
}

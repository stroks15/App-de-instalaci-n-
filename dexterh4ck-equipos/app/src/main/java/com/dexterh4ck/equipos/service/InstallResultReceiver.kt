package com.dexterh4ck.equipos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receptor del resultado real de PackageInstaller.
 * Mantiene la tarea abierta hasta recibir éxito o error del instalador de Android.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val taskId = intent.getStringExtra(CommandService.EXTRA_INSTALL_TASK_ID)
            ?: intent.getStringExtra("task_id")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Log.d("InstallResult", "Se requiere confirmación del usuario en pantalla")
                if (!taskId.isNullOrBlank()) {
                    notifyService(context, taskId, "pending", "Esperando confirmación de Android en la pantalla del equipo.")
                }
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(it)
                    } catch (e: Exception) {
                        Log.e("InstallResult", "No se pudo abrir el diálogo de instalación", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("InstallResult", "Instalación completada con éxito")
                if (!taskId.isNullOrBlank()) {
                    notifyService(context, taskId, "success", "Android confirmó la instalación correctamente.")
                }
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Error de PackageInstaller (status=$status)"
                Log.e("InstallResult", "Instalación fallida (status=$status): $msg")
                if (!taskId.isNullOrBlank()) notifyService(context, taskId, "failed", msg)
            }
        }
    }

    private fun notifyService(context: Context, taskId: String, state: String, message: String) {
        val serviceIntent = Intent(context, CommandService::class.java).apply {
            action = CommandService.ACTION_INSTALL_RESULT
            putExtra(CommandService.EXTRA_INSTALL_TASK_ID, taskId)
            putExtra(CommandService.EXTRA_INSTALL_STATE, state)
            putExtra(CommandService.EXTRA_INSTALL_MESSAGE, message)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (error: Exception) {
            Log.e("InstallResult", "No se pudo notificar el resultado al agente", error)
        }
    }
}

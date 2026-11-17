package com.dexterh4ck.equipos.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receptor del resultado de la instalación vía PackageInstaller.
 * Recibe STATUS_PENDING_USER_ACTION, STATUS_SUCCESS, STATUS_FAILURE_*.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // En Android TV esto significa que se debe mostrar el diálogo de confirmación.
                Log.d("InstallResult", "Se requiere confirmación del usuario en pantalla")
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
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e("InstallResult", "Instalación fallida (status=$status): $msg")
            }
        }
    }
}
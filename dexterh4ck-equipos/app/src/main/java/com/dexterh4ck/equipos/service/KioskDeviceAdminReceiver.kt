package com.dexterh4ck.equipos.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor de administrador de dispositivo.
 * Necesario para funciones de Device Owner: bloqueo de apps (setApplicationHidden),
 * Lock Task robusto, etc.
 *
 * Para activar Device Owner (una sola vez, equipo recién restaurado):
 *   adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("DeviceAdmin", "Admin habilitado")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("DeviceAdmin", "Admin deshabilitado")
    }
}
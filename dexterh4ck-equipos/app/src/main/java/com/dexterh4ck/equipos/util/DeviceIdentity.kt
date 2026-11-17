package com.dexterh4ck.equipos.util

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Utilidad para obtener un identificador único y estable del dispositivo.
 * Intenta usar ANDROID_ID primero; si falla, genera un UUID y lo guarda.
 */
object DeviceIdentity {

    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences("kiosk_agent_prefs", Context.MODE_PRIVATE)

        // Buscar ID guardado previamente
        var deviceId = prefs.getString("device_id", null)
        if (!deviceId.isNullOrEmpty()) {
            return deviceId
        }

        // Intentar obtener ANDROID_ID
        deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        if (deviceId.isNullOrEmpty()) {
            // Generar UUID aleatorio y guardarlo
            deviceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            prefs.edit().putString("device_id", deviceId).apply()
        }

        return deviceId
    }
}
package com.dexterh4ck.equipos.util

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.dexterh4ck.equipos.service.KioskDeviceAdminReceiver

/**
 * Ayudas para funcionalidades de Device Owner / Device Policy.
 *
 * Para tener bloqueo real de apps (ocultar paquetes) y Lock Task robusto,
 * el agente debe ser Device Owner del equipo. Se configura con ADB:
 *
 *   adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver
 *
 * (Requiere equipo recién restaurado de fábrica. En Fire Stick funciona igual vía ADB.)
 */
object DevicePolicyHelper {

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        try {
            dpm(context).isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            false
        }

    fun isAdminActive(context: Context): Boolean =
        try {
            dpm(context).isAdminActive(adminComponent(context))
        } catch (e: Exception) {
            false
        }

    /**
     * Oculta (bloquea) o muestra (desbloquea) una app sin desinstalarla.
     * REQUIERE Device Owner. Devuelve mensaje explicativo.
     */
    fun setAppHidden(context: Context, packageName: String, hidden: Boolean): String {
        return try {
            if (!isDeviceOwner(context)) {
                "BLOQUEO NO DISPONIBLE: $packageName. Se requiere modo Device Owner. " +
                        "Ejecuta en ADB: adb shell dpm set-device-owner com.dexterh4ck.equipos/.service.KioskDeviceAdminReceiver " +
                        "(el equipo debe estar recién restaurado de fábrica)."
            } else {
                dpm(context).setApplicationHidden(adminComponent(context), packageName, hidden)
                if (hidden) "App bloqueada (oculta): $packageName"
                else "App desbloqueada (visible): $packageName"
            }
        } catch (e: Exception) {
            "Error bloqueando $packageName: ${e.message}"
        }
    }

    /**
     * Intenta iniciar Lock Task (anclaje de pantalla) desde una actividad.
     * Con Device Owner + setLockTaskPackages funciona sin confirmación del usuario.
     */
    fun startLockTaskIfPermitted(activity: Activity) {
        try {
            val dpm = dpm(activity)
            if (dpm.isLockTaskPermitted(activity.packageName)) {
                activity.startLockTask()
            }
        } catch (e: Exception) {
            // Sin permisos: el pinning clásico requiere confirmación del usuario
        }
    }

    fun stopLockTaskIfActive(activity: Activity) {
        try {
            if (activity.isInLockTaskMode) activity.stopLockTask()
        } catch (e: Exception) {}
    }

    /**
     * Registra el paquete del agente en la whitelist de Lock Task (requiere Device Owner).
     */
    fun setLockTaskWhitelist(context: Context) {
        try {
            if (isDeviceOwner(context)) {
                dpm(context).setLockTaskPackages(adminComponent(context), arrayOf(context.packageName))
            }
        } catch (e: Exception) {}
    }
}
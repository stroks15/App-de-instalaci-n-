package com.dexterh4ck.equipos.util

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.dexterh4ck.equipos.service.KioskDeviceAdminReceiver
import com.dexterh4ck.equipos.ui.MainActivity

/** Políticas de administración para equipos Android dedicados. */
object DevicePolicyHelper {
    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun adminComponent(context: Context) =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean =
        runCatching { dpm(context).isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    fun isAdminActive(context: Context): Boolean =
        runCatching { dpm(context).isAdminActive(adminComponent(context)) }.getOrDefault(false)

    /**
     * Configura allowlist de Lock Task y registra MainActivity como Home persistente.
     * Sólo funciona después de provisionar esta app como Device Owner mediante ADB/QR.
     */
    fun configureDedicatedDevice(context: Context): String {
        if (!isDeviceOwner(context)) {
            return "Este equipo aún no tiene Device Owner. Instala la app y ejecuta el comando ADB indicado en la guía."
        }
        return try {
            val manager = dpm(context)
            val admin = adminComponent(context)
            manager.setLockTaskPackages(admin, arrayOf(context.packageName))
            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            manager.addPersistentPreferredActivity(
                admin,
                homeFilter,
                ComponentName(context, MainActivity::class.java)
            )
            "Device Owner activo. Kiosk autorizado y Dexterh4ck Equipos registrado como pantalla de inicio."
        } catch (e: Exception) {
            "Device Owner detectado, pero no se pudieron aplicar las políticas: ${e.message}"
        }
    }

    fun setAppHidden(context: Context, packageName: String, hidden: Boolean): String {
        if (!isDeviceOwner(context)) {
            return "BLOQUEO NO DISPONIBLE: se requiere Device Owner. Consulta la guía ADB de Dexterh4ck Equipos."
        }
        return try {
            val changed = dpm(context).setApplicationHidden(adminComponent(context), packageName, hidden)
            if (!changed) "Android rechazó el cambio de visibilidad para $packageName"
            else if (hidden) "App bloqueada (oculta): $packageName" else "App desbloqueada (visible): $packageName"
        } catch (e: Exception) {
            "Error bloqueando $packageName: ${e.message}"
        }
    }

    fun startLockTaskIfPermitted(activity: Activity) {
        try {
            val manager = dpm(activity)
            if (isDeviceOwner(activity) && manager.isLockTaskPermitted(activity.packageName)) {
                activity.startLockTask()
            }
        } catch (_: Exception) {}
    }

    fun stopLockTaskIfActive(activity: Activity) {
        try { activity.stopLockTask() } catch (_: Exception) {}
    }

    fun setLockTaskWhitelist(context: Context) {
        if (isDeviceOwner(context)) {
            runCatching { val allowed = (listOf(context.packageName) + KioskManager.allowedPackages(context)).distinct().toTypedArray(); dpm(context).setLockTaskPackages(adminComponent(context), allowed) }
        }
    }
}

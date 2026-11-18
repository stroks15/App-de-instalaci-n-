package com.dexterh4ck.equipos.util

import android.content.Context
import java.security.MessageDigest

/** Reglas locales persistentes para bloqueos parentales temporales. */
object ParentalControlStore {
    private const val PREFS = "parental_app_rules"

    fun block(context: Context, packageName: String, pin: String, minutes: Int): String {
        require(packageName.matches(Regex("^[A-Za-z0-9_.]+$"))) { "Paquete inválido" }
        require(pin.matches(Regex("^\\d{4,8}$"))) { "El PIN debe contener de 4 a 8 dígitos" }
        require(minutes in 1..10080) { "Duración no válida" }
        val result = DevicePolicyHelper.setAppHidden(context, packageName, true)
        check(!result.startsWith("BLOQUEO NO DISPONIBLE") && !result.startsWith("Error")) { result }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("pin:$packageName", sha256(pin))
            .putLong("until:$packageName", System.currentTimeMillis() + minutes * 60_000L)
            .apply()
        return "$result. Bloqueo temporal activado por $minutes minutos."
    }

    fun unblock(context: Context, packageName: String, pin: String, automatic: Boolean = false): String {
        require(packageName.matches(Regex("^[A-Za-z0-9_.]+$"))) { "Paquete inválido" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("pin:$packageName", null)
        if (!automatic && stored != null) {
            require(pin.isNotBlank() && constantTimeEquals(stored, sha256(pin))) { "PIN parental incorrecto" }
        }
        val result = DevicePolicyHelper.setAppHidden(context, packageName, false)
        check(!result.startsWith("BLOQUEO NO DISPONIBLE") && !result.startsWith("Error")) { result }
        prefs.edit().remove("pin:$packageName").remove("until:$packageName").apply()
        return if (automatic) "$packageName desbloqueada: terminó el tiempo establecido." else result
    }

    fun expire(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.all.keys.filter { it.startsWith("until:") }.forEach { key ->
            val pkg = key.removePrefix("until:")
            if (prefs.getLong(key, Long.MAX_VALUE) <= now) {
                runCatching { unblock(context, pkg, "", automatic = true) }
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
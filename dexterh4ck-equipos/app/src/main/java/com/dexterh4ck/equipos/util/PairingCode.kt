package com.dexterh4ck.equipos.util

import android.content.Context
import java.security.SecureRandom

object PairingCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val PREFS = "dexterh4ck_pairing"
    private const val KEY = "pairing_code"
    private val random = SecureRandom()

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let {
            if (it.matches(Regex("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$"))) return it
        }
        // Los códigos antiguos de 5 caracteres se reemplazan para mantener el formato único de 8.
        val code = buildString { repeat(8) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
        prefs.edit().putString(KEY, code).apply()
        return code
    }
}
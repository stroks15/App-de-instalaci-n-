package com.dexterh4ck.equipos.data

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

/**
 * Stores the device bearer token encrypted with an RSA key held by Android Keystore.
 * The legacy plaintext preference is migrated once and removed after encryption succeeds.
 */
object AgentTokenStore {
    private const val PREFS = "dexterh4ck_secure_tokens"
    private const val ENCRYPTED_TOKEN = "agent_token_rsa"
    private const val KEY_ALIAS = "dexterh4ck_agent_token_key"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    @Synchronized
    fun getToken(context: Context): String? {
        val securePrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = securePrefs.getString(ENCRYPTED_TOKEN, null)
        if (!encrypted.isNullOrBlank()) return runCatching { decrypt(context, encrypted) }.getOrNull()

        val legacyPrefs = context.getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE)
        val legacy = legacyPrefs.getString(AgentConfig.KEY_AGENT_TOKEN, null) ?: return null
        return if (saveToken(context, legacy)) {
            legacyPrefs.edit().remove(AgentConfig.KEY_AGENT_TOKEN).apply()
            legacy
        } else null
    }

    @Synchronized
    fun saveToken(context: Context, token: String): Boolean {
        if (token.isBlank()) return false
        return runCatching {
            val keyStore = loadKeyStore()
            ensureKeyPair(context, keyStore)
            val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(ENCRYPTED_TOKEN, encoded).commit()
        }.getOrDefault(false)
    }

    private fun decrypt(context: Context, encoded: String): String {
        val keyStore = loadKeyStore()
        ensureKeyPair(context, keyStore)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKeyPair(context: Context, keyStore: KeyStore) {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .build()
            generator.initialize(spec)
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS)
                .setSubject(X500Principal("CN=Dexterh4ck Agent Token"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.time)
                .setEndDate(end.time)
                .build()
            generator.initialize(spec)
        }
        generator.generateKeyPair()
    }
}

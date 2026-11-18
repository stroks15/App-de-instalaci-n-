package com.dexterh4ck.control.data

import android.content.Context
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Calendar
import javax.crypto.Cipher
import javax.security.auth.x500.X500Principal

object ControlWireGuardKeyStore {
    private const val PREFS = "dexter_control_wg"
    private const val KEY = "private_key_rsa"
    private const val ALIAS = "dexterh4ck_control_wireguard"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    @Synchronized
    fun getOrCreate(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (!stored.isNullOrBlank()) return KeyPair(Key.fromBase64(decrypt(context, stored)))
        val pair = KeyPair()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val store = loadStore()
        ensureKey(context, store)
        cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).publicKey)
        val encrypted = Base64.encodeToString(cipher.doFinal(pair.privateKey.toBase64().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(KEY, encrypted).commit())
        return pair
    }

    private fun decrypt(context: Context, encoded: String): String {
        val store = loadStore()
        ensureKey(context, store)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, store.getKey(ALIAS, null) as PrivateKey)
        return String(cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun loadStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(context: Context, store: KeyStore) {
        if (store.containsAlias(ALIAS)) return
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            generator.initialize(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1).build())
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            generator.initialize(KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS)
                .setSubject(X500Principal("CN=Dexterh4ck Control WireGuard"))
                .setSerialNumber(BigInteger.valueOf(3)).setStartDate(start.time).setEndDate(end.time).build())
        }
        generator.generateKeyPair()
    }
}

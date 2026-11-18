package com.dexterh4ck.equipos.data

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

/** Persists only the encrypted WireGuard private key; public keys may be sent to the backend. */
object WireGuardKeyStore {
    private const val PREFS = "dexterh4ck_wg_keys"
    private const val PRIVATE_KEY = "private_key_rsa"
    private const val ALIAS = "dexterh4ck_wireguard_identity"
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    @Synchronized
    fun getOrCreate(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(PRIVATE_KEY, null)
        if (!encrypted.isNullOrBlank()) {
            val encoded = decrypt(context, encrypted)
            return KeyPair(Key.fromBase64(encoded))
        }
        val pair = KeyPair()
        val encoded = pair.privateKey.toBase64()
        val ciphertext = encrypt(context, encoded)
        check(prefs.edit().putString(PRIVATE_KEY, ciphertext).commit()) {
            "No se pudo guardar la clave VPN cifrada."
        }
        return pair
    }

    private fun encrypt(context: Context, value: String): String {
        val store = loadKeyStore()
        ensureKeyPair(context, store)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, store.getCertificate(ALIAS).publicKey)
        return Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, value: String): String {
        val store = loadKeyStore()
        ensureKeyPair(context, store)
        val privateKey = store.getKey(ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKeyPair(context: Context, store: KeyStore) {
        if (store.containsAlias(ALIAS)) return
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setKeySize(2048)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .build()
            )
        } else {
            val start = Calendar.getInstance()
            val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
            generator.initialize(
                KeyPairGeneratorSpec.Builder(context)
                    .setAlias(ALIAS)
                    .setSubject(X500Principal("CN=Dexterh4ck WireGuard Identity"))
                    .setSerialNumber(BigInteger.valueOf(2))
                    .setStartDate(start.time)
                    .setEndDate(end.time)
                    .build()
            )
        }
        generator.generateKeyPair()
    }
}

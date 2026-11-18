package com.dexterh4ck.control.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Route
import okhttp3.Response
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Supabase session storage encrypted with an AES-GCM key held by Android Keystore. */
object AuthStore {
    private const val LEGACY_PREFS = "dexter_control_auth"
    private const val SECURE_PREFS = "dexter_control_secure_auth"
    private const val ACCESS = "access_token"
    private const val REFRESH = "refresh_token"
    private const val ACCESS_ENCRYPTED = "access_token_aesgcm"
    private const val REFRESH_ENCRYPTED = "refresh_token_aesgcm"
    private const val KEY_ALIAS = "dexterh4ck_control_auth_aes"
    private const val SUPABASE_URL = "https://jnrnbbkjcvxeplyljkyj.supabase.co"
    private const val API_KEY = "sb_publishable_3GDyrM0JTII5TPZ6sAOJnA_YDtxjbiw"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun accessToken(context: Context): String? = readSecureOrMigrate(context, ACCESS_ENCRYPTED, ACCESS)
    private fun refreshToken(context: Context): String? = readSecureOrMigrate(context, REFRESH_ENCRYPTED, REFRESH)

    @Synchronized
    fun save(context: Context, access: String, refresh: String) {
        require(access.isNotBlank() && refresh.isNotBlank()) { "Supabase tokens must not be blank." }
        val secure = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        val ok = secure.edit()
            .putString(ACCESS_ENCRYPTED, encrypt(context, access))
            .putString(REFRESH_ENCRYPTED, encrypt(context, refresh))
            .commit()
        check(ok) { "No se pudo guardar la sesión cifrada." }
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun readSecureOrMigrate(context: Context, secureKey: String, legacyKey: String): String? {
        val secure = context.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
        val ciphertext = secure.getString(secureKey, null)
        if (!ciphertext.isNullOrBlank()) {
            val value = runCatching { decrypt(context, ciphertext) }.getOrNull()
            if (value != null) return value
            secure.edit().remove(secureKey).apply()
        }
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyAccess = legacyPrefs.getString(ACCESS, null)
        val legacyRefresh = legacyPrefs.getString(REFRESH, null)
        if (!legacyAccess.isNullOrBlank() && !legacyRefresh.isNullOrBlank()) {
            runCatching { save(context, legacyAccess, legacyRefresh) }
        }
        return if (secureKey == ACCESS_ENCRYPTED) legacyAccess else legacyRefresh
    }

    private fun secretKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(context: Context, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(context))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(context: Context, encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 12) { "Invalid encrypted token." }
        val iv = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(context), javax.crypto.spec.GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    fun client(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        return OkHttpClient.Builder().addInterceptor { chain ->
            val token = accessToken(appContext)
            val request = chain.request().newBuilder().apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            chain.proceed(request)
        }.authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (responseCount(response) >= 2) return null
                val refresh = refreshToken(appContext) ?: return null
                return try {
                    val body = JSONObject().put("refresh_token", refresh).toString().toRequestBody(jsonType)
                    val request = Request.Builder()
                        .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                        .header("apikey", API_KEY).post(body).build()
                    OkHttpClient().newCall(request).execute().use { result ->
                        if (!result.isSuccessful) {
                            clear(appContext)
                            return null
                        }
                        val json = JSONObject(result.body?.string() ?: return null)
                        val access = json.optString("access_token")
                        val nextRefresh = json.optString("refresh_token", refresh)
                        if (access.isBlank() || nextRefresh.isBlank()) return null
                        save(appContext, access, nextRefresh)
                        response.request.newBuilder().header("Authorization", "Bearer $access").build()
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }).build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var previous = response.priorResponse
        while (previous != null) {
            count++
            previous = previous.priorResponse
        }
        return count
    }
}

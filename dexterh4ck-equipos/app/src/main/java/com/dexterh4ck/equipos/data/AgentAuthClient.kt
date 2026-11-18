package com.dexterh4ck.equipos.data

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Email/password login and authenticated device registration. */
object AgentAuthClient {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(25, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).build()

    fun loginAndRegister(context: Context, backendUrl: String, deviceId: String, deviceName: String, email: String, password: String): JSONObject {
        require(email.isNotBlank() && password.isNotBlank()) { "Ingresa el correo y la contraseña." }
        val loginBody = JSONObject().put("email", email.trim()).put("password", password).toString().toRequestBody(jsonType)
        val loginRequest = Request.Builder().url(backendUrl.trimEnd('/') + "/api/auth/login").post(loginBody).build()
        val session = client.newCall(loginRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("Inicio de sesión rechazado"))
            JSONObject(raw)
        }
        val accessToken = session.optString("access_token")
        require(accessToken.isNotBlank()) { "El backend no devolvió una sesión válida." }
        val wireGuardKeys = WireGuardKeyStore.getOrCreate(context)
        val registrationBody = JSONObject().put("device_id", deviceId).put("device_name", deviceName.trim())
            .put("public_key", wireGuardKeys.publicKey.toBase64())
            .put("device_role", "agent").put("device_type", "android_tv")
            .put("platform", if (android.os.Build.MANUFACTURER.contains("Amazon", true)) "fireos" else "android")
            .put("app_version", "1.0.0").toString().toRequestBody(jsonType)
        val registrationRequest = Request.Builder().url(backendUrl.trimEnd('/') + "/api/agent/register-auth")
            .header("Authorization", "Bearer " + accessToken).post(registrationBody).build()
        val registration = client.newCall(registrationRequest).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("No se pudo registrar el equipo"))
            JSONObject(raw)
        }
        val token = registration.optString("agent_token")
        require(token.isNotBlank()) { "El backend no devolvió la credencial del agente." }
        check(AgentTokenStore.saveToken(context, token)) { "No se pudo guardar la credencial segura del equipo." }
        val device = registration.optJSONObject("device")
        val vpn = registration.optJSONObject("vpn")
        context.getSharedPreferences(AgentConfig.PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(AgentConfig.KEY_DEVICE_ID, deviceId).putString(AgentConfig.KEY_DEVICE_NAME, deviceName.trim())
            .putString(AgentConfig.KEY_SERVER_URL, backendUrl.trimEnd('/')).putBoolean(AgentConfig.KEY_IS_PAIRED, true)
            .putString("account_user_id", session.optString("user_id"))
            .putString("account_email", email.trim())
            .putString("assigned_vpn_ip", vpn?.optString("assigned_ip") ?: device?.optString("assigned_ip"))
            .putString("vpn_endpoint", vpn?.optString("endpoint"))
            .putString("vpn_server_public_key", vpn?.optString("server_public_key"))
            .putBoolean("vpn_enabled", vpn?.optBoolean("enabled") == true)
            .apply()
        return registration
    }
}

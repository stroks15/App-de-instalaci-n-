package com.dexterh4ck.control.data

import android.content.Context
import android.os.Build
import com.dexterh4ck.control.vpn.ControlWireGuardTunnel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

object ControlVpnBootstrap {
    private const val PREFS = "dexter_control_wg"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()

    fun register(context: Context, accessToken: String): JSONObject {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("control_device_id", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("control_device_id", it).apply()
            }
        val pair = ControlWireGuardKeyStore.getOrCreate(context)
        val name = "Dexterh4ck Control · " + Build.MODEL.take(50)
        val body = JSONObject().put("device_id", deviceId).put("device_name", name)
            .put("public_key", pair.publicKey.toBase64()).toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url(ApiConfig.BASE_URL.trimEnd('/') + "/api/devices/register-auth")
            .header("Authorization", "Bearer " + accessToken)
            .post(body).build()
        val result = client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                throw IllegalStateException(message.ifBlank { "No se pudo registrar Control (HTTP " + response.code + ")." })
            }
            JSONObject(raw)
        }
        val device = result.optJSONObject("device")
        val vpn = result.optJSONObject("vpn")
        prefs.edit()
            .putString("assigned_vpn_ip", vpn?.optString("assigned_ip") ?: device?.optString("assigned_ip"))
            .putString("vpn_endpoint", vpn?.optString("endpoint"))
            .putString("vpn_server_public_key", vpn?.optString("server_public_key"))
            .putBoolean("vpn_enabled", vpn?.optBoolean("enabled") == true)
            .apply()
        return result
    }

    fun reconnect(context: Context) {
        ControlWireGuardTunnel.disconnect()
        connect(context)
    }

    fun connect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ip = prefs.getString("assigned_vpn_ip", null)?.substringBefore('/').orEmpty()
        val endpoint = prefs.getString("vpn_endpoint", null).orEmpty()
        val serverKey = prefs.getString("vpn_server_public_key", null).orEmpty()
        require(ip.isNotBlank() && endpoint.isNotBlank() && serverKey.isNotBlank()) {
            "La configuración VPN del controlador está incompleta."
        }
        val pair = ControlWireGuardKeyStore.getOrCreate(context)
        val configuration = """
            [Interface]
            PrivateKey = ${pair.privateKey.toBase64()}
            Address = $ip/32
            MTU = 1280

            [Peer]
            PublicKey = $serverKey
            Endpoint = $endpoint
            AllowedIPs = 10.88.0.0/16
            PersistentKeepalive = 25
        """.trimIndent()
        ControlWireGuardTunnel.connect(context, configuration)
    }
}

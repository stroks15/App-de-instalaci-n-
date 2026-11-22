package com.dexterh4ck.control.ui

import android.content.Intent
import android.app.Activity
import android.net.VpnService
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.ApiConfig
import com.dexterh4ck.control.data.ControlVpnBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var submit: Button

    private val googleRedirect = "com.dexterh4ck.control://login-callback"
    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectVpnAndContinue()
        else {
            Toast.makeText(this, "VPN no autorizada; Control continuará sin túnel privado.", Toast.LENGTH_LONG).show()
            openDashboard()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleOAuthCallback(intent)) return
        if (!AuthStore.accessToken(this).isNullOrBlank()) {
            registerAndContinue()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "DEXTERH4CK CONTROL\nInicia sesión para administrar tus equipos"
            textSize = 22f
            gravity = Gravity.CENTER
        })
        email = EditText(this).apply {
            hint = "Correo electrónico"
            inputType = 33
        }
        password = EditText(this).apply {
            hint = "Contraseña"
            inputType = 129
        }
        submit = Button(this).apply { text = "Iniciar sesión" }
        val google = Button(this).apply { text = "Continuar con Google" }

        root.addView(email, LinearLayout.LayoutParams(-1, -2))
        root.addView(password, LinearLayout.LayoutParams(-1, -2))
        root.addView(submit, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "O CONTINUAR CON"
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 8)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(google, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        submit.setOnClickListener { signIn() }
        google.setOnClickListener { signInWithGoogle() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun signInWithGoogle() {
        try {
            val authUrl = Uri.parse("${ApiConfig.SUPABASE_URL.trimEnd('/')}/auth/v1/authorize")
                .buildUpon()
                .appendQueryParameter("provider", "google")
                .appendQueryParameter("redirect_to", googleRedirect)
                .build()
            startActivity(Intent(Intent.ACTION_VIEW, authUrl))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir Google: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleOAuthCallback(callback: Intent?): Boolean {
        val uri = callback?.data ?: return false
        if (uri.scheme != "com.dexterh4ck.control" || uri.host != "login-callback") return false

        val fragment = uri.fragment.orEmpty()
        val params = Uri.parse("https://oauth-callback.invalid/?$fragment")
        val access = params.getQueryParameter("access_token")
        val refresh = params.getQueryParameter("refresh_token")
        val error = params.getQueryParameter("error_description")
            ?: params.getQueryParameter("error")

        if (!error.isNullOrBlank()) {
            Toast.makeText(this, "Google no autorizó el acceso: $error", Toast.LENGTH_LONG).show()
            return true
        }
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            Toast.makeText(this, "Google no devolvió una sesión válida. Revisa la configuración OAuth.", Toast.LENGTH_LONG).show()
            return true
        }
        AuthStore.save(this, access, refresh)
        registerAndContinue()
        return true
    }

    private fun signIn() {
        val user = email.text.toString().trim()
        val pass = password.text.toString()
        if (user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Ingresa correo y contraseña", Toast.LENGTH_SHORT).show()
            return
        }
        submit.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val body = JSONObject().put("email", user).put("password", pass)
                        .toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("${ApiConfig.BASE_URL.trimEnd('/')}/api/auth/login")
                        .post(body).build()
                    OkHttpClient.Builder()
                        .connectTimeout(25, TimeUnit.SECONDS)
                        .readTimeout(35, TimeUnit.SECONDS)
                        .callTimeout(45, TimeUnit.SECONDS)
                        .build().newCall(req).execute().use { response ->
                        val payload = JSONObject(response.body?.string() ?: "{}")
                        if (!response.isSuccessful) throw IllegalStateException(
                            payload.optString("error", payload.optString("message", "Error de autenticación HTTP ${response.code}"))
                        )
                        payload
                    }
                }
                val access = result.optString("access_token")
                val refresh = result.optString("refresh_token")
                if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("No se recibió una sesión válida")
                AuthStore.save(this@LoginActivity, access, refresh)
                registerAndContinue()
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, e.message ?: "No se pudo iniciar sesión", Toast.LENGTH_LONG).show()
                submit.isEnabled = true
            }
        }
    }

    private fun registerAndContinue() {
        val token = AuthStore.accessToken(this)
        if (token.isNullOrBlank()) {
            openDashboard()
            return
        }
        lifecycleScope.launch {
            try {
                val registration = withContext(Dispatchers.IO) {
                    ControlVpnBootstrap.register(this@LoginActivity, token)
                }
                val vpn = registration.optJSONObject("vpn")
                if (vpn?.optBoolean("enabled") == true) {
                    val permission = VpnService.prepare(this@LoginActivity)
                    if (permission != null) vpnPermissionLauncher.launch(permission)
                    else connectVpnAndContinue()
                } else {
                    val status = vpn?.optString("status") ?: "not_configured"
                    Toast.makeText(this@LoginActivity, "Control registrado. VPN: $status", Toast.LENGTH_LONG).show()
                    openDashboard()
                }
            } catch (error: Exception) {
                Toast.makeText(this@LoginActivity, "No se pudo registrar Control en la VPN: ${error.message}", Toast.LENGTH_LONG).show()
                openDashboard()
            }
        }
    }

    private fun connectVpnAndContinue() {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ControlVpnBootstrap.connect(this@LoginActivity)
                }
                Toast.makeText(this@LoginActivity, "WireGuard conectado.", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(this@LoginActivity, "VPN no conectada: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                openDashboard()
            }
        }
    }

    private fun openDashboard() {
        startActivity(Intent(this, DeviceListActivity::class.java))
        finish()
    }
}

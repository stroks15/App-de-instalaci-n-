package com.dexterh4ck.control.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.R
import com.dexterh4ck.control.data.ApiConfig
import com.dexterh4ck.control.data.AuthStore
import com.dexterh4ck.control.data.Device
import com.dexterh4ck.control.data.KioskApi
import com.dexterh4ck.control.data.TaskRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Busca APKs públicos de un repositorio GitHub (assets de Releases y archivos .apk
 * del árbol del repositorio) y permite instalarlos en uno o varios equipos.
 */
class GithubApkActivity : AppCompatActivity() {
    private data class ApkEntry(val name: String, val url: String, val source: String)

    private lateinit var api: KioskApi
    private lateinit var repositoryInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var transferRing: ProgressBar
    private lateinit var transferPercent: TextView
    private lateinit var transferStage: TextView
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private var targetDeviceId: String? = null
    private var targetDeviceName: String = ""
    private var foundApks: List<ApkEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetDeviceId = intent.getStringExtra("device_id")?.takeIf { it.isNotBlank() }
        targetDeviceName = intent.getStringExtra("device_name").orEmpty()

        api = Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(AuthStore.client(this))
            .build().create(KioskApi::class.java)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(243, 246, 251))
        }
        val title = TextView(this).apply {
            text = "APKs desde GitHub"
            textSize = 23f
            setTextColor(Color.rgb(8, 75, 170))
            setPadding(0, 0, 0, dp(8))
        }
        repositoryInput = EditText(this).apply {
            hint = "https://github.com/usuario/repositorio"
            singleLine = true
            setText(getPreferences(MODE_PRIVATE).getString("github_repo_url", ""))
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val searchButton = Button(this).apply {
            text = "Buscar APKs"
            setOnClickListener { searchRepository() }
        }
        progress = ProgressBar(this).apply { visibility = View.GONE }
        status = TextView(this).apply {
            text = "Agrega un repositorio público de GitHub. Se revisarán sus Releases y los archivos .apk del repositorio."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(12))
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            addView(results)
        }
        root.addView(title)
        root.addView(repositoryInput, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(searchButton, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(progress)
        val transferFrame = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, dp(142))
        }
        transferRing = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressDrawable = getDrawable(R.drawable.circular_progress_ring)
            layoutParams = FrameLayout.LayoutParams(dp(116), dp(116), Gravity.CENTER)
        }
        transferPercent = TextView(this).apply {
            text = "0%"
            textSize = 23f
            setTextColor(Color.rgb(8, 75, 170))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
        }
        transferFrame.addView(transferRing)
        transferFrame.addView(transferPercent)
        transferStage = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(transferFrame)
        transferFrame.tag = "transferFrame"
        root.addView(transferStage)
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun searchRepository() {
        val parsed = parseRepository(repositoryInput.text.toString())
        if (parsed == null) {
            toast("Ingresa una URL válida: https://github.com/usuario/repositorio")
            return
        }
        getPreferences(MODE_PRIVATE).edit()
            .putString("github_repo_url", repositoryInput.text.toString().trim()).apply()
        progress.visibility = View.VISIBLE
        status.text = "Buscando APKs en GitHub..."
        results.removeAllViews()
        lifecycleScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { discoverApks(parsed.first, parsed.second) }
                foundApks = found
                progress.visibility = View.GONE
                if (found.isEmpty()) {
                    status.text = "No se encontraron APKs. Sube un APK como asset de una Release o agrega un archivo .apk al repositorio."
                } else {
                    status.text = "Se encontraron ${found.size} APK(s). Selecciona el que deseas instalar."
                    found.forEach { apk ->
                        val button = Button(this@GithubApkActivity).apply {
                            text = "${apk.name}\n${apk.source}"
                            isAllCaps = false
                            setOnClickListener { chooseTargets(apk) }
                        }
                        results.addView(button, LinearLayout.LayoutParams(-1, dp(64)).apply {
                            bottomMargin = dp(8)
                        })
                    }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                status.text = "No se pudo consultar GitHub: ${e.message ?: "error desconocido"}"
            }
        }
    }

    private fun discoverApks(owner: String, repo: String): List<ApkEntry> {
        val found = linkedMapOf<String, ApkEntry>()
        val releasesUrl = "https://api.github.com/repos/$owner/$repo/releases?per_page=20"
        val releases = getJson(releasesUrl)
        if (releases is org.json.JSONArray) {
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                val tag = release.optString("tag_name", "Release")
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", true) && url.startsWith("https://")) {
                        found[url] = ApkEntry(name, url, "Release $tag")
                    }
                }
            }
        }

        val repoInfo = getJson("https://api.github.com/repos/$owner/$repo") as? JSONObject
            ?: return found.values.toList()
        val branch = repoInfo.optString("default_branch").takeIf { it.isNotBlank() } ?: "main"
        val encodedBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val tree = getJson("https://api.github.com/repos/$owner/$repo/git/trees/$encodedBranch?recursive=1") as? JSONObject
        val entries = tree?.optJSONArray("tree")
        if (entries != null) {
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val path = item.optString("path")
                if (item.optString("type") != "blob" || !path.endsWith(".apk", true)) continue
                val rawPath = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val url = "https://raw.githubusercontent.com/$owner/$repo/$encodedBranch/$rawPath"
                found.putIfAbsent(url, ApkEntry(path.substringAfterLast('/'), url, "Rama $branch · $path"))
            }
        }
        return found.values.toList()
    }

    private fun getJson(url: String): Any? {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Dexterh4ck-Control-Android")
            .get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) return null
                throw IllegalStateException("GitHub respondió HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return null
            return if (body.trimStart().startsWith("[")) org.json.JSONArray(body) else JSONObject(body)
        }
    }

    private fun chooseTargets(apk: ApkEntry) {
        val fixedId = targetDeviceId
        if (!fixedId.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle("Instalar ${apk.name}")
                .setMessage("Se enviará la instalación a $targetDeviceName. Si el equipo no es Device Owner, Android puede solicitar confirmación en la TV.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Instalar") { _, _ -> installOnDevices(apk, listOfNotNull(fixedId)) }
                .show()
            return
        }
        lifecycleScope.launch {
            try {
                status.text = "Cargando equipos vinculados..."
                val response = withContext(Dispatchers.IO) { api.getDevices() }
                if (!response.isSuccessful) {
                    toast("No se pudieron cargar los equipos (HTTP ${response.code()})")
                    return@launch
                }
                val devices = response.body().orEmpty()
                if (devices.isEmpty()) {
                    toast("No hay equipos vinculados")
                    return@launch
                }
                showDeviceSelection(apk, devices)
            } catch (e: Exception) {
                toast("Error al cargar equipos: ${e.message}")
            }
        }
    }

    private fun showDeviceSelection(apk: ApkEntry, devices: List<Device>) {
        val checked = BooleanArray(devices.size) { true }
        val labels = devices.map { "${it.name} · ${it.status}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Equipos para instalar · ${apk.name}")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Instalar en seleccionados") { _, _ ->
                val ids = devices.filterIndexed { index, _ -> checked[index] }.map { it.device_id }
                if (ids.isEmpty()) toast("Selecciona al menos un equipo")
                else installOnDevices(apk, ids)
            }
            .show()
    }

    private fun showTransferProgress(percent: Int, message: String) {
        val frame = (findViewById<View>(android.R.id.content) as? android.view.ViewGroup)
            ?.let { root -> findFrame(root) }
        frame?.visibility = View.VISIBLE
        transferStage.visibility = View.VISIBLE
        transferRing.progress = percent.coerceIn(0, 100)
        transferPercent.text = "${percent.coerceIn(0, 100)}%"
        transferStage.text = message
    }

    private fun findFrame(group: android.view.ViewGroup): FrameLayout? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is FrameLayout && child.tag == "transferFrame") return child
            if (child is android.view.ViewGroup) findFrame(child)?.let { return it }
        }
        return null
    }

    private fun installOnDevices(apk: ApkEntry, deviceIds: List<String>) {
        progress.visibility = View.GONE
        status.text = "Conectando directamente desde GitHub. El APK no se subirá a Supabase."
        showTransferProgress(0, "Preparando descarga directa desde GitHub…")
        lifecycleScope.launch {
            var completed = 0
            var failed = 0
            for ((index, id) in deviceIds.withIndex()) {
                try {
                    val created = withContext(Dispatchers.IO) {
                        api.createTask(TaskRequest(device_id = id, type = "install", payload = apk.url))
                    }
                    if (!created.isSuccessful || created.body() == null) {
                        failed++
                        continue
                    }
                    val taskId = created.body()!!.id
                    var attempts = 0
                    var terminal = false
                    while (attempts < 86400 && !terminal) {
                        delay(2000)
                        attempts++
                        val response = withContext(Dispatchers.IO) { api.getTask(taskId) }
                        if (!response.isSuccessful) continue
                        val task = response.body()?.task ?: continue
                        val raw = task.result
                        val progressData = runCatching {
                            val json = JSONObject(raw ?: "{}")
                            val phase = json.optString("phase")
                            val percent = json.optInt("progress", 0)
                            val label = when (phase) {
                                "download" -> "Descargando APK desde GitHub al equipo ${index + 1}/${deviceIds.size}…"
                                "downloaded" -> "APK descargado en el equipo ${index + 1}/${deviceIds.size}."
                                "install" -> "Preparando instalación en el equipo ${index + 1}/${deviceIds.size}…"
                                else -> "Procesando instalación en el equipo ${index + 1}/${deviceIds.size}…"
                            }
                            percent to label
                        }.getOrNull()
                        if (progressData != null) showTransferProgress(progressData.first, progressData.second)
                        if (task.status == "awaiting_user") showTransferProgress(100, "APK descargado. Acepta la instalación en la TV (equipo ${index + 1}/${deviceIds.size}); puede esperar hasta 48 h.")
                        if (task.status == "completed" || task.status == "done") { completed++; terminal = true }
                        if (task.status == "failed" || task.status == "expired") { failed++; terminal = true }
                        status.text = "Equipos completados: $completed · Fallidos: $failed · En proceso: ${deviceIds.size - completed - failed}"
                    }
                    if (!terminal) failed++
                } catch (_: Exception) {
                    failed++
                }
            }
            showTransferProgress(if (failed == 0) 100 else transferRing.progress, "Proceso finalizado: $completed completados · $failed fallidos.")
            status.text = "Instalación terminada. Completados: $completed · Fallidos: $failed. Los APK públicos de GitHub se descargan directamente en cada equipo, sin pasar por la nube de KioskGrid."
            toast("Completados: $completed · Fallidos: $failed")
        }
    }

    private fun parseRepository(value: String): Pair<String, String>? {
        val clean = value.trim().removeSuffix(".git").trimEnd('/')
        val match = Regex("^https://github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$", RegexOption.IGNORE_CASE)
            .matchEntire(clean) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

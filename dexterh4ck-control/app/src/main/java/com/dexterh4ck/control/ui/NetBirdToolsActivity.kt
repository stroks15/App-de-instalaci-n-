package com.dexterh4ck.control.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.dexterh4ck.control.data.adb.NativeAdbClient
import com.dexterh4ck.control.data.adb.RemoteDeviceOperations
import com.dexterh4ck.control.data.netbird.NetBirdManager
import com.dexterh4ck.control.data.netbird.NetBirdPeerDatabase
import com.dexterh4ck.control.data.netbird.NetBirdPeerEntity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compose console for direct NetBird-overlay ADB administration.
 * The device list is intentionally explicit: operators paste the assigned peer IPs.
 */
class NetBirdToolsActivity : ComponentActivity() {
    private lateinit var adb: NativeAdbClient
    private lateinit var operations: RemoteDeviceOperations
    private var peerText by mutableStateOf("")
    private var output by mutableStateOf("Añade las IP NetBird de tus equipos, una por línea.")
    private var shellCommand by mutableStateOf("getprop ro.product.model")
    private var packageName by mutableStateOf("")
    private var peerAlias by mutableStateOf("Equipo Android")
    private var savedPeers by mutableStateOf<List<NetBirdPeerEntity>>(emptyList())
    private var busy by mutableStateOf(false)
    private val progress = mutableStateMapOf<String, String>()
    private val telemetry = mutableStateMapOf<String, String>()

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installSelected(uri)
    }
    private val pushPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pushSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adb = NativeAdbClient(this)
        operations = RemoteDeviceOperations(adb)
        lifecycleScope.launch {
            NetBirdPeerDatabase.get(this@NetBirdToolsActivity).peers().observeAll().collect { savedPeers = it }
        }
        setContent {
            MaterialTheme {
                val vpn = remember { NetBirdManager.status(this@NetBirdToolsActivity) }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("KioskGrid · Herramientas NetBird", style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Red privada", style = MaterialTheme.typography.titleMedium)
                            Text("Cliente NetBird: ${if (vpn.appInstalled) "instalado" else "no instalado"}")
                            Text("Transporte VPN: ${if (vpn.vpnTransportActive) "activo" else "inactivo"}")
                            Text("IPs locales: ${vpn.overlayAddresses.ifEmpty { listOf("No detectadas") }.joinToString()}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { NetBirdManager.openOfficialClient(this@NetBirdToolsActivity) }) { Text("Abrir NetBird") }
                                Button(onClick = { NetBirdManager.openInstallPage(this@NetBirdToolsActivity) }) { Text("Instalar") }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = peerText,
                        onValueChange = { peerText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("IPs NetBird de los equipos") },
                        placeholder = { Text("100.64.0.2\n100.64.0.3") },
                        minLines = 2
                    )
                    Text("Se usa ADB TCP 5555. El equipo remoto debe tener ADB TCP habilitado y haber autorizado la clave RSA de este controlador.")
                    OutlinedTextField(value = peerAlias, onValueChange = { peerAlias = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Alias del equipo (para guardar)") })
                    Button(enabled = !busy && peers().isNotEmpty() && peerAlias.isNotBlank(), onClick = { savePeers() }) { Text("Guardar IPs e identidad en Room") }
                    savedPeers.forEach { saved -> Text("${saved.alias} · ${saved.netbirdIp} · ${saved.status}") }
                    OutlinedTextField(value = shellCommand, onValueChange = { shellCommand = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Comando adb shell") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && peers().isNotEmpty() && shellCommand.isNotBlank(), onClick = { runAction("Ejecutando comando") { adb.shell(it, shellCommand) } }) { Text("Ejecutar") }
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { runAction("Comprobando Device Owner") { operations.checkDeviceOwnerPrerequisites(it).let { check -> "pm list users:\n${check.usersOutput}\ndumpsys account:\n${check.accountsOutput}\nCuentas detectadas: ${check.likelyBlockedByAccounts}" } } }) { Text("Precheck Owner") }
                    }
                    OutlinedTextField(value = packageName, onValueChange = { packageName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Paquete Android") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && peers().isNotEmpty() && packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")), onClick = { runAction("Limpiando datos") { operations.clearAppData(it, packageName) } }) { Text("Limpiar datos") }
                        Button(enabled = !busy && peers().isNotEmpty() && packageName.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")), onClick = { runAction("Desinstalando") { operations.uninstallApp(it, packageName) } }) { Text("Desinstalar") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { loadTelemetry() }) { Text("Telemetría") }
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { apkPicker.launch(arrayOf("application/vnd.android.package-archive")) }) { Text("Instalar APK") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { runAction("Aplicando Device Owner") { operations.setDeviceOwner(it, "com.dexterh4ck.equipos", "com.dexterh4ck.equipos.service.KioskDeviceAdminReceiver") } }) { Text("Establecer Device Owner") }
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { pushPicker.launch(arrayOf("*/*")) }) { Text("Enviar archivo") }
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { runAction("Reiniciando") { operations.reboot(it) } }) { Text("Reiniciar") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { runAction("Bloqueando pantalla") { operations.lockScreen(it) } }) { Text("Bloquear") }
                        Button(enabled = !busy && peers().isNotEmpty(), onClick = { runAction("Wi-Fi desactivado") { operations.setWifi(it, false) } }) { Text("Apagar Wi-Fi") }
                    }
                    OutlinedTextField(
                        value = output,
                        onValueChange = { output = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Consola / resultado") },
                        minLines = 4
                    )
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    progress.forEach { (ip, stage) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(ip, style = MaterialTheme.typography.titleSmall)
                                Text(stage)
                            }
                        }
                    }
                    telemetry.forEach { (ip, data) ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text("Telemetría · $ip", style = MaterialTheme.typography.titleMedium)
                                Text(data)
                            }
                        }
                    }
                    Text("Las operaciones masivas se ejecutan con concurrencia limitada. El resultado por IP se conserva en esta pantalla.")
                }
            }
        }
    }

    private fun savePeers() {
        val targets = peers()
        if (targets.isEmpty()) { output = "No hay IPs NetBird válidas."; return }
        busy = true
        lifecycleScope.launch {
            try {
                val dao = NetBirdPeerDatabase.get(this@NetBirdToolsActivity).peers()
                val results = operations.runOnDevices(targets) { ip ->
                    val serial = adb.shell(ip, "getprop ro.serialno").substringAfter("\n").trim().takeIf { it.isNotBlank() && it != "unknown" }
                    val androidId = adb.shell(ip, "settings get secure android_id").substringAfter("\n").trim()
                    val hardwareId = serial ?: androidId.takeIf { it.isNotBlank() && it != "null" } ?: throw IllegalStateException("El equipo no devolvió serial ni Android ID.")
                    dao.upsert(NetBirdPeerEntity(
                        peerId = hardwareId,
                        hardwareId = hardwareId,
                        alias = peerAlias.trim(),
                        netbirdIp = ip,
                        status = "reachable",
                        lastSeenEpochMs = System.currentTimeMillis()
                    ))
                    "Identidad guardada: $hardwareId"
                }
                output = results.entries.joinToString("\n") { (ip, result) -> "$ip: ${result.getOrElse { it.message ?: "Error" }}" }
            } finally { busy = false }
        }
    }

    private fun peers(): List<String> = (peerText.lines().map(String::trim) + savedPeers.map { it.netbirdIp })
        .filter { it.matches(Regex("100\\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)")) }
        .distinct()

    private fun runAction(stage: String, action: suspend (String) -> String) {
        val targets = peers()
        if (targets.isEmpty()) { output = "No hay IPs NetBird válidas."; return }
        busy = true
        lifecycleScope.launch {
            try {
                val results = operations.runOnDevices(targets) { ip ->
                    progress[ip] = "Conectando · $stage"
                    action(ip)
                }
                output = results.entries.joinToString("\n\n") { (ip, result) ->
                    progress[ip] = if (result.isSuccess) "Éxito" else "Error"
                    "$ip\n${result.getOrElse { it.message ?: "Error desconocido" }}"
                }
            } finally { busy = false }
        }
    }

    private fun loadTelemetry() {
        val targets = peers()
        busy = true
        lifecycleScope.launch {
            try {
                val results = operations.runOnDevices(targets) { ip ->
                    progress[ip] = "Consultando batería, almacenamiento y sistema…"
                    val t = operations.telemetry(ip)
                    telemetry[ip] = "Modelo: ${t.model}\nAndroid: ${t.androidVersion}\nBatería:\n${t.battery}\nAlmacenamiento:\n${t.storage}"
                    "Telemetría recibida"
                }
                output = results.entries.joinToString("\n") { (ip, result) -> "$ip: ${if (result.isSuccess) "OK" else result.exceptionOrNull()?.message}" }
            } finally { busy = false }
        }
    }

    private fun installSelected(uri: Uri) = runFileOperation(uri, "Instalando APK") { ip, file ->
        progress[ip] = "Enviando APK e instalando…"
        adb.install(ip, file)
    }

    private fun pushSelected(uri: Uri) = runFileOperation(uri, "Enviando archivo") { ip, file ->
        progress[ip] = "Enviando archivo a /sdcard/Download…"
        adb.push(ip, file, "/sdcard/Download/${file.name}")
        "Archivo enviado"
    }

    private fun runFileOperation(uri: Uri, stage: String, action: suspend (String, File) -> String) {
        busy = true
        lifecycleScope.launch {
            var file: File? = null
            try {
                val selectedFile = withContext(Dispatchers.IO) { copyToCache(uri) }
                file = selectedFile
                val targets = peers()
                if (targets.isEmpty()) throw IllegalStateException("No hay IPs NetBird válidas.")
                val results = operations.runOnDevices(targets) { ip ->
                    progress[ip] = "Conectando…"
                    action(ip, selectedFile)
                }
                output = results.entries.joinToString("\n\n") { (ip, result) ->
                    progress[ip] = if (result.isSuccess) "Éxito" else "Error"
                    "$ip · ${result.getOrElse { it.message ?: "Error" }}"
                }
            } catch (e: Exception) {
                output = "$stage: ${e.message}"
            } finally {
                file?.delete()
                busy = false
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.substringAfterLast('/')?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "upload.bin"
        val file = File(cacheDir, "${System.currentTimeMillis()}_$name")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("No se pudo leer el archivo seleccionado.")
        return file
    }
}

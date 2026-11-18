package com.dexterh4ck.control.data.adb

import android.content.Context
import dev.mobile.dadb.AdbKeyPair
import dev.mobile.dadb.Dadb
import dev.mobile.dadb.InstallResult
import dev.mobile.dadb.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ADB-over-TCP client for devices whose ADB daemon is explicitly enabled and authorized.
 * Android 11+ Wireless debugging normally uses TLS pairing and a dynamic port; port 5555
 * is only available when the target exposes legacy adb tcpip mode.
 */
class NativeAdbClient(context: Context) {
    private val keyDirectory = File(context.filesDir, "adb").apply { mkdirs() }
    private val privateKey = File(keyDirectory, "adbkey")
    private val publicKey = File(keyDirectory, "adbkey.pub")

    private fun keyPair(): AdbKeyPair {
        require(keyDirectory.exists() || keyDirectory.mkdirs()) { "No se pudo crear el directorio privado de claves ADB." }
        return if (privateKey.exists() && publicKey.exists()) {
            AdbKeyPair.read(privateKey, publicKey)
        } else {
            AdbKeyPair.create(privateKey, publicKey)
        }
    }

    suspend fun shell(ip: String, command: String): String = withContext(Dispatchers.IO) {
        requireValidIp(ip)
        require(command.isNotBlank() && command.length <= 4096)
        Dadb.create(ip, 5555, keyPair()).use { adb ->
            val result = adb.shell(command)
            "exit=${result.exitCode}\n${result.output}"
        }
    }

    suspend fun install(ip: String, apk: File): String = withContext(Dispatchers.IO) {
        requireValidIp(ip)
        require(apk.isFile && apk.extension.equals("apk", true)) { "Selecciona un archivo APK válido." }
        Dadb.create(ip, 5555, keyPair()).use { adb ->
            when (val result = adb.install(apk)) {
                is InstallResult.Success -> "Instalación completada"
                is InstallResult.Failure -> throw IllegalStateException(result.reason)
            }
        }
    }

    suspend fun push(ip: String, source: File, destinationName: String): String = withContext(Dispatchers.IO) {
        requireValidIp(ip)
        require(source.isFile)
        val safeName = destinationName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.isNotBlank())
        Dadb.create(ip, 5555, keyPair()).use { adb ->
            when (val result = adb.push(source, "/sdcard/Download/$safeName")) {
                is SyncResult.Success -> "Archivo enviado a /sdcard/Download/$safeName"
                is SyncResult.Failure -> throw IllegalStateException(result.toString())
            }
        }
    }

    suspend fun pingPort(ip: String, timeoutMs: Int = 2500): Boolean = withContext(Dispatchers.IO) {
        requireValidIp(ip)
        runCatching {
            Socket().use { it.connect(InetSocketAddress(ip, 5555), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    private fun requireValidIp(ip: String) {
        require(ip.matches(Regex("100\\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)\\.(?:25[0-5]|2[0-4][0-9]|[01]?\\d?\\d)"))) {
            "La IP no pertenece al rango CGNAT NetBird 100.64.0.0/10."
        }
    }
}

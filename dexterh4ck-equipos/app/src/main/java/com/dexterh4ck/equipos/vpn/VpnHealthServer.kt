package com.dexterh4ck.equipos.vpn

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/** Minimal TCP health endpoint bound only to the assigned VPN address, never to Wi-Fi/LAN. */
object VpnHealthServer {
    private const val PORT = 8787
    @Volatile private var listener: ServerSocket? = null

    @Synchronized
    fun start(context: Context, assignedIp: String) {
        if (listener?.isClosed == false) return
        val address = InetAddress.getByName(assignedIp.substringBefore('/'))
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(address, PORT), 8)
        listener = socket
        thread(name = "KioskGrid-VPN-Health", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 3000
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                        val requestLine = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) { }
                        val ok = requestLine.startsWith("GET /health ")
                        val body = if (ok) {
                            """{"ok":true,"device":"${context.packageName}"}"""
                        } else {
                            """{"ok":false,"error":"not_found"}"""
                        }
                        val status = if (ok) "200 OK" else "404 Not Found"
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(header.toByteArray(StandardCharsets.US_ASCII))
                            write(bytes)
                            flush()
                        }
                    }
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        listener = null
    }
}

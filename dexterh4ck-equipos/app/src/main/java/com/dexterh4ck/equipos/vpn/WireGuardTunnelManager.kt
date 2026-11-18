package com.dexterh4ck.equipos.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/** Process-wide backend so Activity and foreground service share one tunnel instance. */
object WireGuardTunnelManager {
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName(): String = "kioskgrid-equipos"
        override fun onStateChange(newState: Tunnel.State) {
            android.util.Log.i("KioskGridVPN", "Tunnel state: $newState")
        }
    }

    @Synchronized
    fun connect(context: Context, configuration: String) {
        val parsed = Config.parse(BufferedReader(StringReader(configuration)))
        val instance = backend ?: GoBackend(context.applicationContext).also { backend = it }
        instance.setState(tunnel, Tunnel.State.UP, parsed)
    }

    @Synchronized
    fun disconnect() {
        backend?.setState(tunnel, Tunnel.State.DOWN, null)
    }
}

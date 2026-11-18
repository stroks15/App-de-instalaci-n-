package com.dexterh4ck.equipos.vpn

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

/** Thin wrapper over the official embeddable WireGuard Android backend. */
class WireGuardTunnelManager(context: Context) {
    private val backend = GoBackend(context.applicationContext)
    private val tunnel = object : Tunnel {
        override fun getName(): String = "kioskgrid-equipos"
        override fun onStateChange(newState: Tunnel.State) {
            android.util.Log.i("KioskGridVPN", "Tunnel state: $newState")
        }
    }

    fun connect(configuration: String) {
        val parsed = Config.parse(BufferedReader(StringReader(configuration)))
        backend.setState(tunnel, Tunnel.State.UP, parsed)
    }

    fun disconnect() {
        backend.setState(tunnel, Tunnel.State.DOWN, null)
    }
}

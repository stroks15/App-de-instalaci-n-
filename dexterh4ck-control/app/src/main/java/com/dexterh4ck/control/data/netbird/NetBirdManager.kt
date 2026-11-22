package com.dexterh4ck.control.data.netbird

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import java.net.NetworkInterface

/**
 * NetBird Android is the supported VPN engine. The controller does not embed a second
 * WireGuard engine or attempt to inject setup credentials into another app.
 */
object NetBirdManager {
    const val PACKAGE_NAME = "io.netbird.client"

    data class Status(
        val appInstalled: Boolean,
        val vpnTransportActive: Boolean,
        val netBirdInterfaceFound: Boolean,
        val overlayAddresses: List<String>
    ) {
        val connected: Boolean get() = vpnTransportActive && (netBirdInterfaceFound || overlayAddresses.isNotEmpty())
    }

    fun status(context: Context): Status {
        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
                PACKAGE_NAME, android.content.pm.PackageManager.PackageInfoFlags.of(0)
            ) else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
        }.isSuccess

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.getNetworkCapabilities(cm.activeNetwork)
        val vpn = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        val netBirdIf = interfaces.any { it.isUp && !it.isLoopback && (it.name.contains("tun", true) || it.name.contains("wt", true)) }
        val addresses = interfaces.filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            .mapNotNull { address ->
                val ip = address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                val octets = ip.split('.').mapNotNull(String::toIntOrNull)
                if (octets.size == 4 && octets[0] == 100 && octets[1] in 64..127) ip else null
            }.distinct()
        return Status(installed, vpn, netBirdIf, addresses)
    }

    fun openOfficialClient(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }

    fun openInstallPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

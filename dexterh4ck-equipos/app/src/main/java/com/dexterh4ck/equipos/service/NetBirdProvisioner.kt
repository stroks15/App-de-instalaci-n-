package com.dexterh4ck.equipos.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.dexterh4ck.equipos.service.KioskDeviceAdminReceiver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Provisioning bridge for the official NetBird Android application.
 *
 * Important platform constraints:
 * - Android's NetBird client does not currently expose an Android managed-configuration
 *   channel for setup keys. Do not call DevicePolicyManager.setApplicationRestrictions()
 *   with a made-up "setupKey" key: it is not consumed by the Android client.
 * - A setup key is only passed to a local NetBird CLI when that binary is actually present
 *   and root execution is available. The key is never persisted or logged by this class.
 * - Without a CLI, the user must enroll in the official NetBird UI.
 */
class NetBirdProvisioner(private val context: Context) {
    companion object {
        private const val TAG = "NetBirdMDM"
        const val NETBIRD_PACKAGE = "io.netbird.client"
    }

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, KioskDeviceAdminReceiver::class.java)

    data class Result(
        val success: Boolean,
        val message: String,
        val cliOutput: String = ""
    )

    fun isDeviceOwner(): Boolean =
        runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Detects VPN transport and NetBird's CGNAT overlay address without assuming tun0. */
    fun networkStatus(): NetworkStatus {
        val interfaces = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val overlay = interfaces.asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .filter(::isNetBirdIpv4)
            .distinct()
            .toList()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val vpnActive = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        return NetworkStatus(vpnActive, overlay)
    }

    data class NetworkStatus(val vpnTransportActive: Boolean, val overlayAddresses: List<String>) {
        val connected: Boolean get() = vpnTransportActive && overlayAddresses.isNotEmpty()
    }

    /**
     * Enroll with a setup key only when a root-accessible NetBird CLI is installed.
     * On ordinary Android, opens the official client and returns manual enrollment guidance.
     */
    fun enroll(setupKey: String): Result {
        require(setupKey.isNotBlank() && setupKey.length <= 256) { "Setup Key inválida." }

        if (hasRoot()) {
            val cli = runRootCommand("command -v netbird")
            if (cli.first && cli.second.lineSequence().any { it.trim().startsWith("/") }) {
                val output = runRootCommand("netbird up --setup-key ${shellQuote(setupKey)}")
                return Result(
                    output.first,
                    if (output.first) "NetBird recibió la solicitud de conexión mediante CLI root."
                    else "NetBird CLI devolvió un error.",
                    output.second
                )
            }
        }

        val launched = openOfficialClient()
        return Result(
            false,
            if (launched) {
                "Se abrió NetBird. En la app: menú > Change Server > Add this device with a setup key; introduce la clave y pulsa Change, luego Connect."
            } else {
                "Instala la aplicación oficial NetBird y completa el alta con Setup Key."
            }
        )
    }

    /**
     * Device Owner may request Android Always-on VPN for a package that implements VpnService.
     * This does not enroll the peer or bypass the VPN permission/first-run flow.
     */
    fun requestAlwaysOnVpn(): Result {
        if (!isDeviceOwner()) return Result(false, "Se requiere Device Owner para configurar Always-on VPN.")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return Result(false, "Always-on VPN requiere Android 7.0 o posterior.")
        }
        if (!isPackageInstalled(NETBIRD_PACKAGE)) {
            return Result(false, "NetBird no está instalado.")
        }
        return try {
            dpm.setAlwaysOnVpnPackage(admin, NETBIRD_PACKAGE, false)
            Result(true, "Android configuró NetBird como VPN siempre activa. La conexión debe completarse desde NetBird.")
        } catch (e: Exception) {
            Result(false, "Android rechazó Always-on VPN: ${e.message}")
        }
    }

    /** Opens the system battery-optimization screen; Android does not allow silently whitelisting another app. */
    fun openBatteryOptimizationSettings(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isIgnoringBatteryOptimizations(NETBIRD_PACKAGE)) {
            return true
        }
        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    private fun openOfficialClient(): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(NETBIRD_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun hasRoot(): Boolean = runRootCommand("id").let { it.first && it.second.contains("uid=0") }

    private fun runRootCommand(command: String): Pair<Boolean, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(12, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false to "Timeout ejecutando comando root."
            } else {
                val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                (process.exitValue() == 0) to output.take(8000)
            }
        } catch (e: Exception) {
            false to (e.message ?: "No hay acceso root.")
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun isNetBirdIpv4(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull(String::toIntOrNull)
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 &&
            parts.drop(2).all { it in 0..255 }
    }
}

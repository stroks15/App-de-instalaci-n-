package com.dexterh4ck.control.data.adb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** High-level, allowlisted operations layered on the authenticated DADB transport. */
class RemoteDeviceOperations(private val adb: NativeAdbClient) {

    data class Telemetry(
        val battery: String,
        val storage: String,
        val model: String,
        val androidVersion: String
    )

    data class DeviceOwnerCheck(
        val usersOutput: String,
        val accountsOutput: String,
        val likelyBlockedByAccounts: Boolean
    )

    suspend fun telemetry(ip: String): Telemetry = coroutineScope {
        val battery = async { adb.shell(ip, "dumpsys battery") }
        val storage = async { adb.shell(ip, "df -h /data") }
        val model = async { adb.shell(ip, "getprop ro.product.model") }
        val version = async { adb.shell(ip, "getprop ro.build.version.release") }
        Telemetry(battery.await(), storage.await(), model.await(), version.await())
    }

    suspend fun checkDeviceOwnerPrerequisites(ip: String): DeviceOwnerCheck = coroutineScope {
        val users = async { adb.shell(ip, "pm list users") }
        val accounts = async { adb.shell(ip, "dumpsys account") }
        val usersText = users.await()
        val accountsText = accounts.await()
        val hasAccounts = Regex("Account \\{").containsMatchIn(accountsText) ||
            Regex("Accounts:").containsMatchIn(accountsText) &&
            !accountsText.contains("Accounts: 0")
        DeviceOwnerCheck(usersText, accountsText, hasAccounts)
    }

    /** Returns the exact shell output; Android may reject this on provisioned/user-configured devices. */
    suspend fun setDeviceOwner(ip: String, packageName: String, receiverClass: String): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(receiverClass.matches(Regex("[A-Za-z0-9_.$]+")))
        val preflight = checkDeviceOwnerPrerequisites(ip)
        val warning = if (preflight.likelyBlockedByAccounts) {
            "ADVERTENCIA: dumpsys account indica cuentas configuradas. Android puede impedir Device Owner.\n"
        } else ""
        val command = "dpm set-device-owner $packageName/$receiverClass"
        return warning + "PRECHECK pm list users:\n${preflight.usersOutput}\nPRECHECK dumpsys account:\n${preflight.accountsOutput}\nCOMANDO: $command\nRESPUESTA:\n" +
            adb.shell(ip, command)
    }

    suspend fun reboot(ip: String): String = adb.shell(ip, "reboot")
    suspend fun lockScreen(ip: String): String = adb.shell(ip, "input keyevent 26")

    suspend fun clearAppData(ip: String, packageName: String): String {
        requirePackage(packageName)
        return adb.shell(ip, "pm clear $packageName")
    }

    suspend fun uninstallApp(ip: String, packageName: String): String {
        requirePackage(packageName)
        return adb.shell(ip, "pm uninstall $packageName")
    }

    suspend fun setWifi(ip: String, enabled: Boolean): String =
        adb.shell(ip, if (enabled) "svc wifi enable" else "svc wifi disable")

    suspend fun runOnDevices(
        ips: List<String>,
        parallelism: Int = 3,
        operation: suspend (String) -> String
    ): Map<String, Result<String>> = coroutineScope {
        require(parallelism in 1..8)
        val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
        ips.distinct().map { ip ->
            async {
                semaphore.acquire()
                try {
                    ip to runCatching { operation(ip) }
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll().toMap()
    }

    private fun requirePackage(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
            "Nombre de paquete no válido."
        }
    }
}

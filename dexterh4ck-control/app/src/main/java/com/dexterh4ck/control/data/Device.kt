package com.dexterh4ck.control.data

/**
 * Representa un equipo gestionado remotamente.
 */
data class Device(
    val id: String,
    val device_id: String,
    val name: String,
    val type: String = "android_tv",
    val platform: String = "android",
    val app_version: String = "1.0.0",
    val status: String = "offline",
    val last_seen: String? = null,
    val created_at: String? = null,
    val assigned_ip: String? = null,
    val vpn_enabled: Boolean = false,
    val vpn_status: String? = null
) {
    fun isOnline(): Boolean = status == "online"
    fun isBusy(): Boolean = status == "busy"
}

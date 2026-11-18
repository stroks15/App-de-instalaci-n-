package com.dexterh4ck.equipos.data

/** Datos de alta/heartbeat del agente. */
data class DeviceRegistration(
    val device_id: String,
    val hardware_id: String,
    val pairing_code: String,
    val name: String,
    val type: String = "android_tv",
    val platform: String = "android",
    val app_version: String = "1.0.0",
    val status: String = "online",
    val last_seen: String? = null
)

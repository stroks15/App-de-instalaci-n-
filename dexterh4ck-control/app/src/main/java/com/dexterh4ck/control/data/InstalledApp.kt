package com.dexterh4ck.control.data

/**
 * Representa una aplicación instalada en un dispositivo.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSystemApp: Boolean = false
)
package com.dexterh4ck.control.data

/**
 * Aplicación instalada reportada por el agente Android.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String = "",
    val isSystemApp: Boolean = false,
    val sourceDir: String = ""
)

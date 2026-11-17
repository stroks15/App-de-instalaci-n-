package com.dexterh4ck.control.data

/**
 * Respuesta detallada de /api/devices/{deviceId}
 */
data class DeviceDetail(
    val device: Device,
    val tasks: List<Task>?,
    val installed_apps: List<InstalledApp>?,
    val apps_updated_at: String?
)

/**
 * Respuesta de /api/tasks/{taskId}
 */
data class TaskDetail(
    val task: Task
)

/**
 * Modelo de app instalada reportada por el agente.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean = false,
    val sourceDir: String = "",
    val versionName: String = ""
)
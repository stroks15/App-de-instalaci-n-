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


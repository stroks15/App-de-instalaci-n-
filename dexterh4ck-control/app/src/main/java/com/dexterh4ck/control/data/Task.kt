package com.dexterh4ck.control.data

/**
 * Modelo de tarea enviada al agente (Dexterh4ck Equipos).
 */
data class Task(
    val id: String,
    val device_id: String,
    val type: String,       // install, uninstall, launch, list_apps, device_info, reboot, pin_app
    val payload: String,    // URL del APK o nombre del paquete
    val status: String,     // queued, in_progress, completed, failed
    val result: String? = null,
    val error: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null
)

/**
 * Cuerpo para crear una tarea (POST /api/tasks).
 */
data class TaskRequest(
    val device_id: String,
    val type: String,
    val payload: String,
    val status: String = "queued"
)
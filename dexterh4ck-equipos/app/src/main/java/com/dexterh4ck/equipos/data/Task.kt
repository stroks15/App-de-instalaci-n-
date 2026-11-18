package com.dexterh4ck.equipos.data

/**
 * Modelo de tarea remota que el agente recibe del controlador (Dexterh4ck Control).
 *
 * Tipos de tarea (type):
 * - install      → payload = URL pública del APK (Supabase Storage)
 * - uninstall    → payload = nombre del paquete (ej: com.spotify.tv)
 * - launch       → payload = nombre del paquete a abrir
 * - list_apps    → payload = vacío; resultado = JSON con apps instaladas
 * - device_info  → payload = vacío; resultado = JSON con info del equipo
 * - reboot       → payload = vacío; reinicia el equipo (requiere privilegios)
 * - pin_app      → payload = nombre del paquete; intenta fijar la app en el launcher
 */
data class Task(
    val id: String,
    val device_id: String,
    val type: String,
    val payload: String,
    val status: String,
    val result: String? = null,
    val error: String? = null,
    val created_at: String? = null,
    val updated_at: String? = null,
    val expires_at: String? = null
)

/**
 * Cuerpo para actualizar el estado de una tarea (PATCH /api/tasks/{id}).
 */
data class TaskUpdate(
    val id: String,
    val status: String,
    val result: String? = null,
    val error: String? = null
)
/** Respuesta del registro seguro del agente. El token sólo se entrega en el primer alta. */
data class AgentRegistrationResponse(
    val device: Map<String, Any?>? = null,
    val agent_token: String? = null,
    val token_rotated: Boolean? = null
)

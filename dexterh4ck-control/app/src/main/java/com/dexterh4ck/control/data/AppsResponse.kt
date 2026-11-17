package com.dexterh4ck.control.data

/**
 * Respuesta del endpoint /api/apps con las apps instaladas reportadas por el agente.
 */
data class AppsResponse(
    val deviceId: String,
    val apps: List<InstalledApp>,
    val updated_at: String?,
    val task_id: String?,
    val message: String? = null
)
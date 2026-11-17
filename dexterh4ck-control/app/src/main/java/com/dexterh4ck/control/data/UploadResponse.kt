package com.dexterh4ck.control.data

/**
 * Respuesta de la subida de un archivo a Supabase Storage.
 */
data class UploadResponse(
    val path: String,
    val name: String?,
    val size: Long?,
    val url: String,
    val bucket: String
)
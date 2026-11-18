package com.dexterh4ck.control.data

/**
 * Configuración de conexión con el backend Next.js KioskGrid.
 * Supabase se usa para Auth y datos; los endpoints del backend viven en Vercel.
 */
object ApiConfig {
    const val BASE_URL = "https://kioskgrid-backend-stroks15s-projects.vercel.app/"
    const val SUPABASE_URL = "https://jnrnbbkjcvxeplyljkyj.supabase.co/"
    const val APK_BUCKET = "apks"
}

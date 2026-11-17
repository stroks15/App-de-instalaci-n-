package com.dexterh4ck.control.data

/**
 * Configuración de conexión con el backend KioskGrid.
 */
object ApiConfig {
    /**
     * URL pública del proyecto Supabase.
     * El backend Next.js se conecta directamente a la base de datos,
     * por lo que las apps solo necesitan esta URL.
     */
    const val BASE_URL = "https://jnrnbbkjcvxeplyljkyj.supabase.co/"

    const val APK_BUCKET = "apks"
}
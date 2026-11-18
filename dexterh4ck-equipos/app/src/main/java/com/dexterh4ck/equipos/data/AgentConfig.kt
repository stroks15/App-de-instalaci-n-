package com.dexterh4ck.equipos.data

/**
 * Configuración persistida del agente (SharedPreferences).
 */
data class AgentConfig(
    val deviceId: String,
    val deviceName: String,
    val isPaired: Boolean,
    val serverUrl: String
) {
    companion object {
        const val PREF_NAME = "dexterh4ck_agent_config"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_IS_PAIRED = "is_paired"
        const val KEY_AGENT_TOKEN = "agent_token"
        const val KEY_SERVER_URL = "server_url"

        /** URL del backend Next.js desplegado en Vercel. */
        const val DEFAULT_SERVER_URL = "https://kioskgrid-backend-stroks15s-projects.vercel.app"
    }
}
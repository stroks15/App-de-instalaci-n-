package com.dexterh4ck.equipos.data

import retrofit2.Response
import retrofit2.http.*

/**
 * Interfaz de comunicación con el backend KioskGrid (Supabase).
 *
 * Base URL: la URL pública del proyecto Supabase (p.ej. https://xxx.supabase.co/)
 * El backend expone los endpoints de API que se conectan a la base de datos.
 */
interface KioskApi {

    /**
     * Obtiene las tareas pendientes para este dispositivo (polling cada 5s).
     */
    @GET("api/agent/tasks")
    suspend fun getTasks(
        @Query("deviceId") deviceId: String,
        @Query("status") status: String = "queued"
    ): Response<List<Task>>

    /**
     * Reporta el resultado de una tarea (estado + resultado JSON).
     * Usado por el agente después de ejecutar un comando.
     */
    @PATCH("api/agent/tasks")
    suspend fun updateTask(@Body update: TaskUpdate): Response<Map<String, Task>>

    /** Heartbeat authenticated with the device credential issued after Supabase login. */
    @POST("api/agent/heartbeat")
    suspend fun heartbeat(
        @Body heartbeat: DeviceHeartbeat
    ): Response<Map<String, Any>>

    /**
     * Actualiza campos del dispositivo (ej: kiosk_enabled).
     */
    @PUT("api/devices/{deviceId}")
    suspend fun updateDevice(
        @Path("deviceId") deviceId: String,
        @Body updates: Map<String, Any>
    ): Response<DeviceRegistration>
}
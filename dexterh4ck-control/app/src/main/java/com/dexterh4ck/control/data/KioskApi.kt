package com.dexterh4ck.control.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Interfaz de comunicación con el backend KioskGrid (Supabase).
 * Base URL: URL pública del proyecto Supabase (ver ApiConfig).
 */
interface KioskApi {

    // ---------- Dispositivos ----------

    @GET("api/devices")
    suspend fun getDevices(): Response<List<Device>>

    @GET("api/devices/{deviceId}")
    suspend fun getDeviceDetail(@Path("deviceId") deviceId: String): Response<DeviceDetail>

    @PUT("api/devices/{deviceId}")
    suspend fun updateDevice(
        @Path("deviceId") deviceId: String,
        @Body updates: Map<String, Any>
    ): Response<Device>

    @DELETE("api/devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String): Response<Map<String, String>>

    // ---------- Tareas ----------

    @GET("api/tasks")
    suspend fun getTasks(
        @Query("deviceId") deviceId: String? = null,
        @Query("status") status: String? = null,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<List<Task>>

    @POST("api/tasks")
    suspend fun createTask(@Body task: TaskRequest): Response<Task>

    @GET("api/tasks/{taskId}")
    suspend fun getTask(@Path("taskId") taskId: String): Response<TaskDetail>

    // ---------- Apps ----------

    @GET("api/apps")
    suspend fun getInstalledApps(@Query("deviceId") deviceId: String): Response<AppsResponse>

    // ---------- Storage (APKs) ----------

    @Multipart
    @POST("api/storage")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("bucket") bucket: RequestBody
    ): Response<UploadResponse>

    @DELETE("api/storage")
    suspend fun deleteFile(
        @Query("bucket") bucket: String = "apks",
        @Query("path") path: String
    ): Response<Map<String, String>>
}
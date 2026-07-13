package com.gagmate.app.data.api

import com.gagmate.app.data.model.MachineState
import com.gagmate.app.data.model.ApiResponse
import com.gagmate.app.data.model.ProfilesResponse
import com.gagmate.app.data.model.ShotProfile
import retrofit2.Response
import retrofit2.http.*

// Shot history data point for brew replay
data class ShotDataPoint(
    val time: Float = 0f,
    val pressure: Float = 0f,
    val flow: Float = 0f,
    val temperature: Float = 0f
)

data class ShotRecord(
    val id: String = "",
    val timestamp: Long = 0L,
    val profile: String = "",
    val duration: Float = 0f,
    val volume: Float = 0f,
    val data: List<ShotDataPoint> = emptyList()
)

/**
 * Retrofit API interface for the Gagguino ggboard service.
 * Standard endpoints exposed by the ESP32/Arduino web server.
 */
interface GgboardApi {

    @GET("api/state")
    suspend fun getMachineState(): Response<MachineState>

    @GET("api/profiles")
    suspend fun getProfiles(): Response<ProfilesResponse>

    @GET("api/profile")
    suspend fun getActiveProfile(): Response<ShotProfile>

    @GET("api/profile/{id}")
    suspend fun getProfileById(@Path("id") profileId: String): Response<ShotProfile>

    @POST("api/profile")
    suspend fun uploadProfile(@Body profile: ShotProfile): Response<ApiResponse<String>>

    @DELETE("api/profile/{id}")
    suspend fun deleteProfile(@Path("id") profileId: String): Response<ApiResponse<String>>

    @POST("api/command")
    suspend fun sendCommand(@Body command: Map<String, String>): Response<ApiResponse<String>>

    @GET("api/settings")
    suspend fun getSettings(): Response<Map<String, String>>

    @POST("api/command/brew")
    suspend fun startBrew(): Response<ApiResponse<String>>

    @POST("api/command/stop")
    suspend fun stopBrew(): Response<ApiResponse<String>>

    @GET("api/state/history")
    suspend fun getHistory(): Response<List<MachineState>>


    @GET("api/shots")
    suspend fun getShotHistory(): Response<List<ShotRecord>>

    @GET("api/shots/{id}")
    suspend fun getShotDetail(@Path("id") shotId: String): Response<ShotRecord>

    @DELETE("api/shots/{id}")
    suspend fun deleteShotHistory(@Path("id") shotId: String): Response<ApiResponse<String>>

    @POST("api/command/prime")
    suspend fun primePump(): Response<ApiResponse<String>>
    @GET("api/restart")
    suspend fun restartMachine(): Response<ApiResponse<String>>

    companion object {
        const val DEFAULT_PORT = 80
    }
}

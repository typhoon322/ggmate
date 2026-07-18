package com.gagmate.app.data.api

import com.gagmate.app.data.model.MachineState
import com.gagmate.app.data.model.ShotRecordApi
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.model.LatestShotResponse
import com.gagmate.app.data.model.ShotProfile
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Gaggiuino v3 REST API.
 * See https://gaggiuino.github.io/#/rest-api/rest-api
 *
 * NOTE: Brew control (start/stop/prime etc.) is NOT available via REST.
 * It requires WebSocket connection to the machine.
 */
interface GgboardApi {

    // ── System ──────────────────────────────────────────────────────

    @GET("api/system/status")
    suspend fun getMachineState(): Response<List<MachineState>>

    // ── Profiles ─────────────────────────────────────────────────────────

    @GET("api/profiles/all")
    suspend fun getProfiles(): Response<List<ProfileRef>>

    @POST("api/profile-select/{id}")
    suspend fun selectProfile(@Path("id") profileId: Int): Response<Map<String, Any>>

    @DELETE("api/profile-select/{id}")
    suspend fun deleteProfile(@Path("id") profileId: Int): Response<Map<String, Any>>

    @POST("api/profile")
    suspend fun uploadProfile(@Body profile: ShotProfile): Response<Map<String, Any>>

    // ── Shots ───────────────────────────────────────────────────────

    @GET("api/shots/latest")
    suspend fun getLatestShotId(): Response<List<LatestShotResponse>>

    @GET("api/shots/{id}")
    suspend fun getShotDetail(@Path("id") shotId: String): Response<ShotRecordApi>

    // ── Settings ────────────────────────────────────────────────────

    @GET("api/settings")
    suspend fun getAllSettings(): Response<Map<String, Any>>

    @GET("api/settings/versions")
    suspend fun getVersions(): Response<Map<String, Any>>

    companion object {
        const val DEFAULT_PORT = 80
    }
}

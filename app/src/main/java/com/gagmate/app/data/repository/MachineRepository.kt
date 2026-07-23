package com.gagmate.app.data.repository

import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.model.ShotRecordApi
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.model.EmbeddedProfile
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.toBrewPhase

/**
 * Repository for accessing Gaggiuino v3 machine data via REST API.
 *
 * Real-time data and control commands go through WebSocket ([MachineSessionManager]).
 * REST is used only for non-real-time operations: history, configuration, uploads.
 */
class MachineRepository {

    private val api get() = GgboardApiClient.getApi()

    /** GET /api/system/status → returns array, unwrap first element. */
    suspend fun getMachineState(): Result<MachineState> = runCatching {
        val response = api.getMachineState()
        if (response.isSuccessful) {
            response.body()?.firstOrNull() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /** GET /api/profiles/all → simple profile references (id, name, selected). */
    suspend fun getProfiles(): Result<List<ProfileRef>> = runCatching {
        val response = api.getProfiles()
        if (response.isSuccessful) {
            response.body() ?: emptyList()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * DELETE /api/profile-select/{id} → delete a profile from the machine.
     * Profile activation/selection now goes through WebSocket c_upd_act_prof_id.
     */
    suspend fun deleteProfile(profileId: Int): Result<Unit> = runCatching {
        val response = api.deleteProfile(profileId)
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /** GET /api/shots/latest → returns [{lastShotId: "7"}]. */
    suspend fun getLatestShotId(): Result<String> = runCatching {
        val response = api.getLatestShotId()
        if (response.isSuccessful) {
            response.body()?.firstOrNull()?.lastShotId
                ?: throw Exception("No shot ID in response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /** GET /api/shots/{id} → full shot record with columnar datapoints. */
    suspend fun getShotDetail(shotId: String): Result<ShotRecordApi> = runCatching {
        val response = api.getShotDetail(shotId)
        if (response.isSuccessful) {
            response.body() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /** POST /api/profile → upload a full profile to the machine. */
    suspend fun uploadProfile(profile: ShotProfile): Result<Unit> = runCatching {
        val response = api.uploadProfile(profile)
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /** GET /api/profile/{id} → full profile with phases. */
    suspend fun getProfileDetail(profileId: String): Result<EmbeddedProfile> = runCatching {
        val response = api.getProfileDetail(profileId)
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
        response.body() ?: throw Exception("Empty response")
    }

    /**
     * Resolve the phase list for a profile for LIVE display, tolerating
     * firmwares that do not expose `GET /api/profile/{id}`.
     *
     * Strategy (live display only — persistence happens via the WS→Room
     * collector in [com.gagmate.app.data.repository.ProfileRepository]):
     *  1. LIVE WebSocket current definition: `g_prof` → `d_prof` (preferred,
     *     this is the authoritative "now" recipe on REST-less firmwares).
     *  2. Last-resort for viewing only: the most recent shot's *embedded*
     *     profile (`GET /api/shots/{id}` → `profile.phases`), matched by name.
     *     This is a HISTORICAL snapshot — never persisted, since changing the
     *     recipe on the machine would make it stale.
     *
     * Returns an empty list only if both sources are unavailable.
     */
    suspend fun fetchProfilePhases(id: String?, name: String): List<BrewPhase> {
        // 1) LIVE WebSocket current definition (preferred).
        val intId = id?.toIntOrNull()
        if (intId != null && name.isNotBlank()) {
            try {
                val ws = AppContainer.machineSession.requestProfilePhases(intId, name, 3500)
                if (ws.isNotEmpty()) return ws
            } catch (_: Exception) { }
        }
        // 2) Fallback: latest shot's embedded profile, matched by name (view only).
        runCatching {
            val latestId = getLatestShotId().getOrNull() ?: return@runCatching null
            val shot = getShotDetail(latestId).getOrNull() ?: return@runCatching null
            shot.profile?.takeIf { it.name == name }?.phases?.takeIf { it.isNotEmpty() }
                ?.map { it.toBrewPhase() }
        }.getOrNull()?.let { return it }
        return emptyList()
    }

    fun updateConnection(host: String, port: Int = 80) {
        val baseUrl = "http://$host:$port"
        GgboardApiClient.updateBaseUrl(baseUrl)
    }
}

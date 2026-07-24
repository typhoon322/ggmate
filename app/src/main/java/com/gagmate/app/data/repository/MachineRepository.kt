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
     * Resolve the phase list for a profile for LIVE display.
     *
     * The Gaggiuino WebSocket `d_prof`/`d_act_prof` payload does NOT carry the
     * per-phase *curve type* (EASE_OUT / EASE_IN_OUT / …): [ProtoDecoder]
     * can only read it as a numeric enum, which the firmware sends as 0, so all
     * WS phases collapse to `variation = "FLAT"` and the chart draws straight
     * lines. The genuine curve type lives **only as a string** in:
     *   1. REST `GET /api/profile/{id}` → `EmbeddedProfile` (`PhaseV3.toBrewPhase`
     *      maps `target.curve` → `variation`, uppercased). May be unsupported on
     *      some firmware; treated as best-effort.
     *   2. The most recent shot's *embedded* profile (`GET /api/shots/{id}` →
     *      `profile.phases`), matched by name or profile id — proven in logs to
     *      carry the real curve strings (e.g. EASE_OUT, EASE_IN_OUT).
     *
     * Strategy (live display only — persistence happens via the WS→Room
     * collector in [com.gagmate.app.data.repository.ProfileRepository]):
     *   1. Fetch live phases from WS `g_prof` (authoritative current values).
     *   2. Independently resolve a *curve-type source* (REST detail, else
     *      shot-embedded). When both exist with matching phase counts, overlay
     *      the real curve types onto the live WS values so the chart renders
     *      eased transitions. Otherwise fall back to the curve source, then WS.
     *
     * Returns an empty list only if every source is unavailable.
     */
    suspend fun fetchProfilePhases(id: String?, name: String): List<BrewPhase> {
        val intId = id?.toIntOrNull()

        // (A) Curve-type source — carries genuine EASE_* strings.
        val restPhases: List<BrewPhase>? = if (intId != null) {
            getProfileDetail(intId.toString()).getOrNull()
                ?.takeIf { it.phases.isNotEmpty() }
                ?.let { it.phases.map { p -> p.toBrewPhase() } }
        } else null
        val shotPhases: List<BrewPhase>? = runCatching {
            val latestId = getLatestShotId().getOrNull() ?: return@runCatching null
            val shot = getShotDetail(latestId).getOrNull() ?: return@runCatching null
            shot.profile
                ?.takeIf { it.name == name || it.id == intId }
                ?.phases?.takeIf { it.isNotEmpty() }
                ?.map { it.toBrewPhase() }
        }.getOrNull()
        val curveSource: List<BrewPhase>? = restPhases ?: shotPhases

        // (B) Live WS definition — authoritative values, but curve type is FLAT.
        val wsPhases: List<BrewPhase>? = if (intId != null && name.isNotBlank()) {
            try {
                AppContainer.machineSession.requestProfilePhases(intId, name, 3500)
                    .takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
        } else null

        return when {
            // Merge: keep live WS values, overlay real curve types by phase index.
            wsPhases != null && curveSource != null && curveSource.size == wsPhases.size -> {
                wsPhases.mapIndexed { i, wp ->
                    val cv = curveSource[i].variation
                    if (cv.isNotBlank() && cv != "FLAT" && cv != "LINEAR") wp.copy(variation = cv) else wp
                }
            }
            curveSource != null -> curveSource
            wsPhases != null -> wsPhases
            else -> emptyList()
        }
    }

    fun updateConnection(host: String, port: Int = 80) {
        val baseUrl = "http://$host:$port"
        GgboardApiClient.updateBaseUrl(baseUrl)
    }
}

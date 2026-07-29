package com.gagmate.app.data.repository

import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.data.model.ProfileRef
import com.gagmate.app.data.model.ShotRecordApi
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.model.EmbeddedProfile
import com.gagmate.app.data.model.BrewPhase

/**
 * Repository for accessing Gaggiuino v3 machine data via REST API.
 *
 * Real-time data and control commands go through WebSocket ([MachineSessionManager]).
 * REST is used only for non-real-time operations: history, configuration, uploads.
 */
class MachineRepository(
    private val localRepo: LocalDataRepository? = null
) {

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
     * Curve-source decision (see project log analysis + CODE_REVIEW §0):
     *   - REST `GET /api/profile/{id}` is DEAD on this firmware — it returns the
     *     SPA index.html, so it cannot supply curve types.
     *   - WS `d_prof` carries phase values but NOT the curve enum (decoder sees
     *     only 0 → always FLAT).
     *   - The ONLY reliable source of real EASE_* curve strings is the profile
     *     EMBEDDED in shot records (`GET /api/shots/{id}` → `profile.phases`,
     *     mirrored into `shot_records.embedded_phases_json`). Those are persisted
     *     by [com.gagmate.app.data.repository.SyncManager.syncShots] and are
     *     available offline.
     *
     * Strategy (live display only — persistence happens via `syncShots`):
     *   1. Curve source = most recent local shot whose embedded profile matches
     *      this profile (by name, or by machine id). Real EASE_* strings.
     *   2. Live WS `g_prof`→`d_prof` *values* (FLAT curve), overlaid onto the
     *      curve source when phase counts match, so the chart shows eased
     *      transitions AND current machine values.
     *   3. Fall back to the curve source alone, then to WS alone.
     *
     * Returns an empty list only if every source is unavailable.
     */
    suspend fun fetchProfilePhases(id: String?, name: String): List<BrewPhase> {
        val intId = id?.toIntOrNull()

        // (A) Curve source — real EASE_* strings from a shot-embedded profile.
        val shotPhases: List<BrewPhase>? = resolveShotPhases(intId, name)

        // (B) Live WS definition — authoritative values, but curve type is FLAT.
        val wsPhases: List<BrewPhase>? = if (intId != null && name.isNotBlank()) {
            try {
                AppContainer.machineSession.requestProfilePhases(intId, name, 3500)
                    .takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
        } else null

        return when {
            // Merge: keep live WS values, overlay real curve types by phase index.
            wsPhases != null && shotPhases != null && shotPhases.size == wsPhases.size -> {
                wsPhases.mapIndexed { i, wp ->
                    val cv = shotPhases[i].variation
                    if (cv.isNotBlank() && cv != "FLAT" && cv != "LINEAR") wp.copy(variation = cv) else wp
                }
            }
            shotPhases != null -> shotPhases
            wsPhases != null -> wsPhases
            else -> emptyList()
        }
    }

    /**
     * Find the most recent locally-stored shot whose embedded profile matches the
     * requested profile, and return its real (EASE_*) phase definitions.
     * Offline-capable; returns null if no matching shot has been synced yet.
     */
    private suspend fun resolveShotPhases(intId: Int?, name: String): List<BrewPhase>? {
        if (name.isBlank() && intId == null) return null
        val repo = localRepo ?: return null
        return runCatching {
            // Prefer name match (machine enforces unique profile names); fall back
            // to machine id when only the id is known.
            val shot = repo.getLatestShotByProfileName(name)
                ?: (intId?.toString()?.let { repo.getLatestShotByProfileId(it) })
                ?: return@runCatching null
            val phases = shot.embeddedPhases().takeIf { it.isNotEmpty() } ?: return@runCatching null
            phases
        }.getOrNull()
    }

    fun updateConnection(host: String, port: Int = 80) {
        val baseUrl = "http://$host:$port"
        GgboardApiClient.updateBaseUrl(baseUrl)
    }
}

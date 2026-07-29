package com.gagmate.app.data.repository

import android.util.Log
import com.gagmate.app.BuildConfig
import com.gagmate.app.data.local.entity.ProfileEntity

import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotRecord
import com.gagmate.app.data.model.toBrewPhase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

/**
 * Coordinates synchronisation between the ggboard machine API and the local Room database.
 *
 * Sync strategy (profiles):
 *   - Machine is default source of truth for unmodified local profiles.
 *   - Locally-modified profiles are kept and later uploaded (overwrite machine).
 *   - When both sides changed → CONFLICT status, user resolves via UI.
 *
 * Shot history & machine settings: machine is always source of truth.
 */
class SyncManager(
    private val localRepo: LocalDataRepository,
    private val machineRepo: MachineRepository,
    private val machineSession: com.gagmate.app.data.session.MachineSessionManager
) {
    private val gson = Gson()

    data class SyncResult(
        val profilesAdded: Int = 0,
        val profilesUpdated: Int = 0,
        val profilesConflicted: Int = 0,
        val profilesUploaded: Int = 0,
        val shotsAdded: Int = 0,
        val errors: List<String> = emptyList()
    )

    /**
     * Run a full bidirectional sync.
     * Safe to call from a ViewModel coroutine – wraps all errors.
     */
    suspend fun fullSync(): SyncResult {
        var profilesAdded = 0
        var profilesUpdated = 0
        var profilesConflicted = 0
        var profilesUploaded = 0
        val errors = mutableListOf<String>()
        try {
            val r = syncProfiles()
            profilesAdded = r.profilesAdded
            profilesUpdated = r.profilesUpdated
            profilesConflicted = r.profilesConflicted
            profilesUploaded = r.profilesUploaded
        } catch (e: Exception) {
            errors.add("Profile sync: ${e.message ?: e}")
        }
        try {
            val r = syncShots()
            profilesAdded += r.profilesAdded
        } catch (e: Exception) {
            errors.add("Shot sync: ${e.message ?: e}")
        }
        try {
            syncSettings()
        } catch (_: Exception) {
            // settings sync is best-effort
        }
        return SyncResult(profilesAdded = profilesAdded, profilesUpdated = profilesUpdated, profilesConflicted = profilesConflicted, profilesUploaded = profilesUploaded, errors = errors.toList())
    }

    /**
     * Upload only locally-modified / local-only profiles.
     * Used for the "Upload pending" button.
     */
    suspend fun uploadPendingProfiles(): SyncResult {
        var profilesUploaded = 0
        val errors = mutableListOf<String>()
        val pending = localRepo.getPendingUploads()
        for (entity in pending) {
            try {
                val profile = entity.toShotProfile()
                machineRepo.uploadProfile(profile).onSuccess {
                    localRepo.markProfileSynced(entity.id, profile.profileId ?: entity.machineProfileId)
                }.onFailure {
                    throw it
                }
                profilesUploaded++
            } catch (e: Exception) {
                errors.add("Upload '${entity.name}': ${e.message ?: e}")
            }
        }
        return SyncResult(profilesUploaded = profilesUploaded, errors = errors.toList())
    }

    // ── Internals ─────────────────────────────────────────────────────

    private suspend fun syncProfiles(): SyncResult {
        var profilesAdded = 0
        var profilesUpdated = 0
        var profilesConflicted = 0
        var profilesUploaded = 0
        val errors = mutableListOf<String>()

        val machineProfiles = try {
            machineRepo.getProfiles().getOrDefault(emptyList())
        } catch (_: Exception) {
            return SyncResult()
        }
        if (BuildConfig.DEBUG)
            Log.d("GagMateProfile", "fullSync: machine returned ${machineProfiles.size} profiles: " +
                machineProfiles.joinToString { "[id=${it.id} name='${it.name}']" })

        val localProfiles = localRepo.getAllProfiles().associateBy { it.machineProfileId }

        for (mp in machineProfiles) {
            val mId = mp.id.toString()
            val local = localProfiles[mId]

            // Fire the WebSocket g_prof request too — the WS→Room collector
            // persists the d_prof/d_act_prof response (by profile name) into
            // phasesJson asynchronously and keeps live target values current.
            if (machineSession.isConnected()) {
                try { machineSession.sendGetProfile(mp.id) } catch (_: Exception) { }
            }

            val saved: ProfileEntity
            if (local == null) {
                saved = ProfileEntity(
                    id = mId,
                    name = mp.name,
                    author = "",
                    notes = "",
                    machineProfileId = mId,
                    phasesJson = "[]",
                    syncStatus = SyncStatus.SYNCED,
                    localUpdatedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis()
                )
                if (BuildConfig.DEBUG)
                    Log.d("GagMateProfile", "fullSync: ADDED profile id=$mId name='${saved.name}'")
                localRepo.saveProfile(saved)
                profilesAdded++
            } else {
                saved = when (local.syncStatus) {
                    SyncStatus.SYNCED -> {
                        // Refresh metadata only; phasesJson is filled below
                        // (we must NOT clobber in-flight/already-synced phases).
                        local.copy(
                            name = mp.name,
                            machineProfileId = mId,
                            syncStatus = SyncStatus.SYNCED,
                            machineUpdatedAt = System.currentTimeMillis()
                        ).also { profilesUpdated++ }
                    }
                    SyncStatus.LOCAL_ONLY -> local
                    SyncStatus.MODIFIED, SyncStatus.CONFLICT -> {
                        // User's local edits win — never overwrite their phases.
                        local.copy(syncStatus = SyncStatus.CONFLICT).also { profilesConflicted++ }
                    }
                }
                localRepo.saveProfile(saved)
            }

            // Seed phasesJson with the REAL (eased) recipe from REST
            // GET /api/profile/{id}. This is the only reliable source of curve
            // types (EASE_OUT / EASE_IN_OUT / …) — the WebSocket d_prof stream
            // only carries FLAT. Storing it here means the profile detail page
            // renders eased curves both live AND offline. Skip user-edited
            // profiles, and skip any profile that already holds real curves so
            // we don't re-fetch REST on every sync.
            //
            // NOTE: do NOT gate this on machineSession.isConnected(). REST uses
            // the same Retrofit client and base URL as the profile list fetch
            // above; it can succeed before the WebSocket handshake completes.
            // The previous WS-only guard caused the seeder to be skipped during
            // the 500 ms startup sync on slow connections, leaving stale garbage
            // phases_json in the DB.
            if (saved.syncStatus == SyncStatus.SYNCED) {
                val needsRefetch = runCatching {
                    val list = gson.fromJson<List<BrewPhase>>(
                        saved.phasesJson, object : TypeToken<List<BrewPhase>>() {}.type
                    )
                    // Empty or all-zero phases are not authoritative: re-fetch.
                    // A real recipe has at least one phase with target > 0 or
                    // meaningful time, and a non-FLAT/LINEAR curve variation.
                    list.isEmpty() || list.all { it.target == 0f && it.time <= 0.1f } ||
                        list.none { it.variation != "FLAT" && it.variation != "LINEAR" }
                }.getOrDefault(true)
                if (needsRefetch) {
                    runCatching {
                        val detail = machineRepo.getProfileDetail(mId).getOrNull()
                        if (detail != null && detail.phases.isNotEmpty()) {
                            val phases = detail.phases.map { it.toBrewPhase() }
                            localRepo.saveProfile(saved.copy(phasesJson = gson.toJson(phases)))
                            if (BuildConfig.DEBUG)
                                Log.d(
                                    "GagMateProfile",
                                    "fullSync: stored REST detail phases (${phases.size}) for id=$mId name='${saved.name}'"
                                )
                        }
                    }
                }
            }
        }

        val localOnly = localRepo.getPendingUploads()
        for (entity in localOnly) {
            if (entity.syncStatus == SyncStatus.LOCAL_ONLY || entity.syncStatus == SyncStatus.MODIFIED) {
                try {
                    val profile = entity.toShotProfile()
                    machineRepo.uploadProfile(profile).onSuccess {
                        localRepo.markProfileSynced(entity.id, profile.profileId ?: entity.machineProfileId)
                        profilesUploaded++
                    }.onFailure { throw it }
                } catch (e: Exception) {
                    errors.add("Upload '${entity.name}': ${e.message ?: e}")
                }
            }
        }

        return SyncResult(
            profilesAdded = profilesAdded,
            profilesUpdated = profilesUpdated,
            profilesConflicted = profilesConflicted,
            profilesUploaded = profilesUploaded,
            errors = errors
        )
    }

    private suspend fun syncShots(): SyncResult {
        var shotsAdded = 0
        try {
            val latestId = machineRepo.getLatestShotId().getOrNull() ?: return SyncResult()
            val latestIdInt = latestId.toIntOrNull() ?: return SyncResult()

            // Skip shots already stored locally so re-syncs are cheap.
            val existing = localRepo.getExistingShotIds().toSet()
            val toFetch = (1..latestIdInt).map { it.toString() }.filter { !existing.contains(it) }
            if (toFetch.isEmpty()) return SyncResult()

            // Fetch missing shots concurrently (bounded) instead of one-by-one.
            val fetched = coroutineScope {
                withContext(Dispatchers.IO.limitedParallelism(4)) {
                    toFetch.map { id -> async { machineRepo.getShotDetail(id).getOrNull() } }.awaitAll()
                }
            }
            fetched.forEach { detail ->
                if (detail != null) {
                    localRepo.saveShot(ShotEntity.fromShotRecord(detail.toShotRecord()))
                    shotsAdded++
                }
            }
        } catch (_: Exception) { }
        return SyncResult(shotsAdded = shotsAdded)
    }

    private suspend fun syncSettings() {
        val settings = try {
            machineRepo.getMachineState().getOrNull()
        } catch (_: Exception) { return } ?: return

        val entries = listOf(
            "status" to if (settings.isBrewing) "brew" else "idle",
            "setpoint" to settings.setpoint.toString(),
            "steam_status" to if (settings.steamOn) "on" else "off",
        )
        localRepo.saveMachineSettings(
            entries.map { (k, v) ->
                MachineSettingsEntity(key = k, value = v, syncStatus = SyncStatus.SYNCED)
            }
        )
    }
}

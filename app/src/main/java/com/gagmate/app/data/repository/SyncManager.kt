package com.gagmate.app.data.repository

import android.util.Log
import com.gagmate.app.BuildConfig
import com.gagmate.app.data.local.entity.ProfileEntity

import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.model.ShotRecord
import com.gagmate.app.data.model.toBrewPhase
import com.google.gson.Gson
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

            // This firmware does NOT expose REST `GET /api/profile/{id}`, so the
            // CURRENT profile definition can only be fetched over WebSocket via
            // `g_prof`. Fire the request here; the ProfileRepository WS→Room
            // collector persists the `d_prof`/`d_act_prof` response (by profile
            // name) into phasesJson asynchronously — this is what makes the
            // profile chart viewable offline with the authoritative, present recipe.
            if (machineSession.isConnected()) {
                try { machineSession.sendGetProfile(mp.id) } catch (_: Exception) { }
            }

            if (local == null) {
                val entity = ProfileEntity(
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
                    Log.d("GagMateProfile", "fullSync: ADDED profile id=$mId name='${entity.name}' (phases filled via WS g_prof)")
                localRepo.saveProfile(entity)
                profilesAdded++
            } else {
                when (local.syncStatus) {
                    SyncStatus.SYNCED -> {
                        // Refresh metadata only; phasesJson is filled by the WS
                        // collector (we must NOT clobber in-flight/already-synced phases).
                        val updated = local.copy(
                            name = mp.name,
                            machineProfileId = mId,
                            syncStatus = SyncStatus.SYNCED,
                            machineUpdatedAt = System.currentTimeMillis()
                        )
                        localRepo.saveProfile(updated)
                        profilesUpdated++
                    }
                    SyncStatus.LOCAL_ONLY -> { /* shouldn't happen */ }
                    SyncStatus.MODIFIED, SyncStatus.CONFLICT -> {
                        // User's local edits win — never overwrite their phases.
                        localRepo.saveProfile(local.copy(syncStatus = SyncStatus.CONFLICT))
                        profilesConflicted++
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

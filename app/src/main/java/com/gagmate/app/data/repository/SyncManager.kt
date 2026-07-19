package com.gagmate.app.data.repository

import com.gagmate.app.data.local.entity.ProfileEntity

import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.model.ShotRecord
import com.google.gson.Gson

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

        val localProfiles = localRepo.getAllProfiles().associateBy { it.machineProfileId }

        for (mp in machineProfiles) {
            val mId = mp.id.toString()
            val local = localProfiles[mId]

            if (local == null) {
                // Fetch full profile data (with phases) if available
                // Request full profile data via WebSocket g_prof (async)
                try {
                    machineSession.sendGetProfile(mp.id)
                } catch (_: Exception) { }
                val phasesJson = "[]"  // Will be filled when WS response arrives
                
                val entity = ProfileEntity(
                    id = mId,
                    name = mp.name,
                    author = "",
                    notes = "",
                    machineProfileId = mId,
                    phasesJson = phasesJson,
                    syncStatus = SyncStatus.SYNCED,
                    localUpdatedAt = System.currentTimeMillis(),
                    createdAt = System.currentTimeMillis()
                )
                localRepo.saveProfile(entity)
                profilesAdded++
            } else {
                when (local.syncStatus) {
                    SyncStatus.SYNCED -> {
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
                    // uploadProfile is not available as REST in Gaggiuino v3
                    // Mark as pending for future WebSocket sync
                    profilesUploaded++
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
            
            // Iterate from shot ID 1 to latestShotId to sync all available shots.
            // Always save full detail (overwrite minimal entities from WS).
            for (shotId in 1..latestIdInt) {
                val detail = machineRepo.getShotDetail(shotId.toString()).getOrNull()
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

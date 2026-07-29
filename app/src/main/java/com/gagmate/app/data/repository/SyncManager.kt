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
    private val machineRepo: MachineRepository
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
        // profile name -> (timestamp ms, real EASE_* phases) for the most recent
        // matching shot. Built from ALL locally-stored shots after fetching.
        val latestPhasesByName = mutableMapOf<String, Pair<Long, List<BrewPhase>>>()
        try {
            val latestId = machineRepo.getLatestShotId().getOrNull() ?: return SyncResult()
            val latestIdInt = latestId.toIntOrNull() ?: return SyncResult()

            // Skip shots already stored locally so re-syncs are cheap.
            val localShots = localRepo.getAllShots()
            val existing = localShots.map { it.id }.toSet()
            val toFetch = (1..latestIdInt).map { it.toString() }
                .filter { !existing.contains(it) }
                .toMutableList()

            // Backfill (v5→v6 compatibility): rows synced BEFORE the migration
            // have embedded_phases_json = '[]' and would otherwise never be
            // re-fetched, so seeding could never happen for users whose shots
            // are all pre-migration. While any SYNCED profile still lacks
            // usable phases, re-fetch those rows so the embedded profile gets
            // captured. Self-limiting: once every profile is seeded, this
            // refetch stops.
            if (anyProfileNeedsSeeding()) {
                localShots.filter { it.embeddedPhases().isEmpty() }
                    .forEach { toFetch.add(it.id) }
            }

            // Fetch shots concurrently (bounded) instead of one-by-one.
            val fetched = if (toFetch.isNotEmpty()) {
                coroutineScope {
                    withContext(Dispatchers.IO.limitedParallelism(4)) {
                        toFetch.map { id -> async { machineRepo.getShotDetail(id).getOrNull() } }.awaitAll()
                    }
                }
            } else emptyList()

            fetched.forEach { detail ->
                if (detail != null) {
                    // toShotRecord() normalizes the timestamp to epoch ms — the
                    // same unit stored in shot_records — so all timestamp
                    // comparisons below stay consistent.
                    val entity = ShotEntity.fromShotRecord(detail.toShotRecord())
                    localRepo.saveShot(entity)  // REPLACE upsert: safe for backfill
                    if (!existing.contains(entity.id)) shotsAdded++
                }
            }

            // Build the per-profile "latest real phases" map from the DB state
            // AFTER saving, so freshly-fetched, backfilled and pre-existing rows
            // all contribute with normalized-ms timestamps.
            localRepo.getAllShots().forEach { shot ->
                val phases = shot.embeddedPhases()
                if (phases.isNotEmpty()) {
                    recordLatestPhases(latestPhasesByName, shot.profileName, shot.timestamp, phases)
                }
            }

            seedProfilePhasesFromShots(latestPhasesByName)
        } catch (_: Exception) { }
        return SyncResult(shotsAdded = shotsAdded)
    }

    /** True if any SYNCED profile still has empty/garbage phases (needs seeding). */
    private suspend fun anyProfileNeedsSeeding(): Boolean {
        val type = object : TypeToken<List<BrewPhase>>() {}.type
        return localRepo.getAllProfiles().any { p ->
            p.syncStatus == SyncStatus.SYNCED && runCatching {
                val list = gson.fromJson<List<BrewPhase>>(p.phasesJson, type) ?: emptyList()
                list.isEmpty() || list.all { it.target == 0f && it.time <= 0.1f }
            }.getOrDefault(true)
        }
    }

    private fun recordLatestPhases(
        map: MutableMap<String, Pair<Long, List<BrewPhase>>>,
        name: String,
        timestamp: Long,
        phases: List<BrewPhase>
    ) {
        val prev = map[name]
        if (prev == null || timestamp > prev.first) {
            map[name] = timestamp to phases
        }
    }

    /**
     * Write real (EASE_*) phase definitions into any SYNCED profile whose
     * stored phases are NOT authoritative (empty or all-zero). Source is the
     * shot-embedded profile — the only reliable curve source on this firmware
     * (REST detail is dead, WS d_prof has no curve enum).
     *
     * Write-guard hierarchy (must never conflict with other write paths):
     *   - Non-SYNCED (MODIFIED/CONFLICT/LOCAL_ONLY) → never touched here.
     *   - SYNCED with meaningful phases (any non-zero target/time) → never
     *     overwritten, even if all curves are LINEAR/FLAT. A user edit pushed
     *     via pushAndSaveIfConfirmed() lands as SYNCED and may legitimately be
     *     all-LINEAR; a stale shot from before the edit must NOT clobber it.
     *   - Only empty / all-zero phasesJson (post-MIGRATION_4_5 wipe, or fresh
     *     rows created by syncProfiles with "[]") is eligible for seeding.
     */
    private suspend fun seedProfilePhasesFromShots(latestPhasesByName: Map<String, Pair<Long, List<BrewPhase>>>) {
        if (latestPhasesByName.isEmpty()) return
        val type = object : TypeToken<List<BrewPhase>>() {}.type
        localRepo.getAllProfiles().forEach { profile ->
            if (profile.syncStatus != SyncStatus.SYNCED) return@forEach
            val notAuthoritative = runCatching {
                val list = gson.fromJson<List<BrewPhase>>(profile.phasesJson, type) ?: emptyList()
                list.isEmpty() || list.all { it.target == 0f && it.time <= 0.1f }
            }.getOrDefault(true)
            if (!notAuthoritative) return@forEach
            val match = latestPhasesByName[profile.name] ?: return@forEach
            if (match.second.isEmpty()) return@forEach
            localRepo.saveProfile(
                profile.copy(
                    phasesJson = gson.toJson(match.second),
                    localUpdatedAt = System.currentTimeMillis()
                )
            )
            if (BuildConfig.DEBUG)
                Log.d("GagMateProfile", "syncShots: seeded ${match.second.size} phases into profile '${profile.name}' from shot-embedded profile")
        }
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

package com.gagmate.app.data.repository

import android.util.Log
import com.gagmate.app.BuildConfig
import com.gagmate.app.data.local.entity.ProfileEntity

import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.MIN_PHASE_SECONDS
import com.gagmate.app.data.model.ShotRecord
import com.gagmate.app.data.model.toBrewPhase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*

/**
 * Coordinates synchronisation between the ggboard machine API and the local Room database.
 *
 * Sync strategy — the Gaggiuino main board is the SINGLE source of truth.
 * The local DB is a one-way, read-only mirror of the board:
 *   - Machine profiles unconditionally overwrite local rows (any local
 *     MODIFIED/CONFLICT state is discarded and reset to the machine version).
 *   - Local rows whose machine profile no longer exists are deleted.
 *   - Pushing local edits to the machine is DISABLED for now
 *     ([uploadPendingProfiles] is a no-op; see ProfileRepository).
 *
 * Shot history & machine settings: machine is always source of truth.
 */
class SyncManager(
    private val localRepo: LocalDataRepository,
    private val machineRepo: MachineRepository
) {
    private val gson = Gson()

    companion object {
        /** Shown when any local→machine profile push is attempted. */
        const val PUSH_DISABLED_MESSAGE =
            "Profile push is disabled: the machine is the single source of truth"
    }

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
     * DISABLED — pushing local profile data to the machine is not supported
     * for now: the Gaggiuino main board is the single source of truth and the
     * local DB is a read-only mirror. Kept as a no-op so existing UI wiring
     * (the "Upload pending" button) degrades gracefully.
     */
    suspend fun uploadPendingProfiles(): SyncResult {
        return SyncResult(errors = listOf(PUSH_DISABLED_MESSAGE))
    }

    // ── Internals ─────────────────────────────────────────────────────

    private suspend fun syncProfiles(): SyncResult {
        var profilesAdded = 0
        var profilesUpdated = 0

        val machineProfiles = try {
            machineRepo.getProfiles().getOrDefault(emptyList())
        } catch (_: Exception) {
            return SyncResult()
        }
        if (BuildConfig.DEBUG)
            Log.d("GagMateProfile", "fullSync: machine returned ${machineProfiles.size} profiles: " +
                machineProfiles.joinToString { "[id=${it.id} name='${it.name}']" })

        val allLocal = localRepo.getAllProfiles()
        val localProfiles = allLocal.associateBy { it.machineProfileId }

        for (mp in machineProfiles) {
            val mId = mp.id.toString()
            val local = localProfiles[mId]

            if (local == null) {
                // The Gen3 /api/profiles/all response carries ONLY id/name/selected
                // — no phase data (verified against a real machine response). So we
                // create the mirror row with an empty phasesJson and let
                // [seedProfilePhasesFromShots] (shot-embedded profile.phases) fill it
                // in later during syncShots. Opening a profile never selects it active,
                // so we never rely on the machine's g_prof(id) for non-active rows.
                val saved = ProfileEntity(
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
                // Machine is the single source of truth for id/name/selected. The
                // phase data is NOT in this payload; it is populated separately by
                // [seedProfilePhasesFromShots] from shot-embedded profiles (the only
                // side-effect-free source). So we preserve the existing phasesJson
                // here and discard any local MODIFIED/CONFLICT edits (machine wins).
                val wasLocallyEdited = local.syncStatus == SyncStatus.MODIFIED ||
                    local.syncStatus == SyncStatus.CONFLICT
                if (wasLocallyEdited && BuildConfig.DEBUG)
                    Log.d("GagMateProfile", "fullSync: DISCARDED local edits for id=$mId name='${mp.name}' (machine is authoritative)")
                val newPhasesJson = if (wasLocallyEdited) "[]" else local.phasesJson
                val saved = local.copy(
                    name = mp.name,
                    machineProfileId = mId,
                    phasesJson = newPhasesJson,
                    syncStatus = SyncStatus.SYNCED,
                    machineUpdatedAt = System.currentTimeMillis()
                )
                localRepo.saveProfile(saved)
                profilesUpdated++
            }
        }

        // Mirror deletions: a local machine-mirror row whose machine profile no
        // longer exists must go too. Guard on a non-empty machine list so a
        // flaky/empty response can never wipe the whole mirror.
        if (machineProfiles.isNotEmpty()) {
            val machineIds = machineProfiles.map { it.id.toString() }.toSet()
            allLocal.filter {
                it.machineProfileId != null &&
                    it.syncStatus != SyncStatus.LOCAL_ONLY &&
                    it.machineProfileId !in machineIds
            }.forEach { stale ->
                if (BuildConfig.DEBUG)
                    Log.d("GagMateProfile", "fullSync: DELETED stale local profile id=${stale.id} name='${stale.name}' (gone from machine)")
                localRepo.deleteProfile(stale.id)
            }
        }

        return SyncResult(
            profilesAdded = profilesAdded,
            profilesUpdated = profilesUpdated
        )
    }

    private suspend fun syncShots(): SyncResult {
        var shotsAdded = 0
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
            seedProfilesFromAllShots()
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

    /**
     * Build the per-profile "latest real phases" map from ALL locally-stored
     * shots and mirror it into the matching SYNCED profiles. Shared by
     * [syncShots] and the one-time [repairVolumeDrivenPhaseTimes] pass.
     */
    private suspend fun seedProfilesFromAllShots() {
        val latestPhasesByName = mutableMapOf<String, Pair<Long, List<BrewPhase>>>()
        localRepo.getAllShots().forEach { shot ->
            val phases = shot.embeddedPhases()
            if (phases.isNotEmpty()) {
                recordLatestPhases(latestPhasesByName, shot.profileName, shot.timestamp, phases)
            }
        }
        seedProfilePhasesFromShots(latestPhasesByName)
    }

    /**
     * Re-derive durations for flow phases that were stored at the flat
     * [MIN_PHASE_SECONDS] floor (volume-driven, stopped on the global weight) by
     * distributing [totalVolume] across them in proportion to flow rate:
     * `time = remaining_volume / Σ(flowRate)`. Phases that already carry a real
     * duration are left untouched, so the pass is idempotent.
     */
    private fun estimateFromVolume(
        phases: List<BrewPhase>,
        totalVolume: Float
    ): List<BrewPhase> {
        if (totalVolume <= 0f) return phases
        var knownVol = 0f
        val estIdx = mutableListOf<Int>()
        var sumFlow = 0f
        phases.forEachIndexed { i, bp ->
            if (!bp.isFlowType || bp.target <= 0f) return@forEachIndexed
            if (bp.time > MIN_PHASE_SECONDS) {
                knownVol += bp.target * bp.time
            } else {
                estIdx += i
                sumFlow += bp.target
            }
        }
        if (estIdx.isEmpty() || sumFlow <= 0f) return phases
        val remaining = maxOf(0f, totalVolume - knownVol)
        val perPhaseSec = (remaining / sumFlow).coerceIn(MIN_PHASE_SECONDS, 120f)
        return phases.mapIndexed { i, bp ->
            if (i in estIdx) bp.copy(time = perPhaseSec) else bp
        }
    }

    /**
     * One-time repair for shots ingested before flow-based phase timing existed.
     * Those rows stored volume-driven flow phases at the flat floor (or the old
     * 0.1s sliver), so their target curve collapsed. We re-derive those durations
     * from the shot's measured volume (≈ target volume) and re-seed the mirrored
     * profiles. Safe to call on every startup: it returns early once no broken
     * phases remain, and it only touches local data (works offline).
     */
    private var repairDone = false
    suspend fun repairVolumeDrivenPhaseTimes() {
        if (repairDone) return
        repairDone = true
        try {
            val shots = localRepo.getAllShots()
            val needsRepair = shots.any { shot ->
                shot.embeddedPhases().any { bp ->
                    bp.isFlowType && bp.target > 0f && bp.time <= MIN_PHASE_SECONDS
                }
            }
            if (!needsRepair) return
            shots.forEach { shot ->
                val phases = shot.embeddedPhases()
                if (phases.isEmpty()) return@forEach
                val repaired = estimateFromVolume(phases, shot.volume)
                if (repaired != phases) {
                    localRepo.saveShot(shot.copy(embeddedPhasesJson = gson.toJson(repaired)))
                }
            }
            seedProfilesFromAllShots()
            if (BuildConfig.DEBUG)
                Log.d("GagMateProfile", "repairVolumeDrivenPhaseTimes: re-derived volume-driven phase times")
        } catch (_: Exception) { }
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
     * Mirror real (EASE_*) phase definitions into every SYNCED profile from the
     * MOST RECENT matching shot's embedded profile — the only reliable curve
     * source on this firmware (REST detail is dead, WS d_prof has no curve enum).
     *
     * The machine is the single source of truth and local edits are never
     * pushed, so there is nothing to protect: whenever the latest shot-embedded
     * phases differ from what is stored, the stored copy is overwritten. This
     * keeps the local mirror converging to the board's recipe (e.g. after the
     * user edits a profile on the machine's own web UI and pulls a new shot).
     * Identical phases are skipped to avoid write churn. LOCAL_ONLY rows
     * (imports/samples, not machine mirrors) are not touched.
     */
    private suspend fun seedProfilePhasesFromShots(latestPhasesByName: Map<String, Pair<Long, List<BrewPhase>>>) {
        if (latestPhasesByName.isEmpty()) return
        localRepo.getAllProfiles().forEach { profile ->
            if (profile.syncStatus != SyncStatus.SYNCED) return@forEach
            // The /api/profiles/all payload does NOT carry phases on this firmware
            // (it returns only id/name/selected), so the only side-effect-free
            // source is the shot-embedded profile. Backfill profiles that still
            // have no phases; never clobber data we already have.
            if (profile.phasesJson.isNotBlank() && profile.phasesJson != "[]" && profile.phasesJson != "null") return@forEach
            val match = latestPhasesByName[profile.name] ?: return@forEach
            if (match.second.isEmpty()) return@forEach
            val newJson = gson.toJson(match.second)
            if (newJson == profile.phasesJson) return@forEach
            localRepo.saveProfile(
                profile.copy(
                    phasesJson = newJson,
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

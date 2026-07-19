package com.gagmate.app.data.repository

import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.session.MachineSessionManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Repository for profile management.
 *
 * Combines:
 * - Local Room database (offline-first, primary source)
 * - REST API (list, upload, delete from machine)
 * - WebSocket (profile activation via [MachineSessionManager])
 */
class ProfileRepository(
    private val localRepo: LocalDataRepository,
    private val machineRepo: MachineRepository,
    private val session: MachineSessionManager,
    private val syncManager: SyncManager
) {
    private val gson = Gson()
    private val repoScope = CoroutineScope(SupervisorJob())

    init {
        // Subscribe to WS profile data: when d_prof/d_act_prof arrives via WebSocket,
        // automatically update the local profile's phases
        repoScope.launch {
            session.profileDataReceived.collect { (name, phases) ->
                try {
                    val profiles = localRepo.getAllProfiles()
                    val match = profiles.find { it.name == name }
                    if (match != null) {
                        val updated = match.copy(
                            phasesJson = gson.toJson(phases),
                            localUpdatedAt = System.currentTimeMillis()
                        )
                        localRepo.saveProfile(updated)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // ── Observables ──────────────────────────────────────────────

    /** Live list of all profiles from local DB. */
    val profilesFlow: Flow<List<ProfileEntity>> = localRepo.profilesFlow

    /** Number of profiles pending upload. */
    val pendingUploadCount: Flow<Int> = localRepo.pendingUploadCount

    // ── Read ─────────────────────────────────────────────────────

    /** Snapshot of all profiles. */
    suspend fun getAllProfiles(): List<ProfileEntity> = localRepo.getAllProfiles()

    /** Get a single profile by local ID. */
    suspend fun getProfileById(id: String): ProfileEntity? = localRepo.getProfileById(id)

    // ── Sync ────────────────────────────────────────────────────

    /** Sync profiles with the machine (bidirectional). */
    suspend fun syncFromMachine(): SyncManager.SyncResult = syncManager.fullSync()

    // ── Activation (via WebSocket) ──────────────────────────────

    /** Activate a profile by its machine ID via WebSocket. */
    fun activateProfile(machineProfileId: Int) {
        session.selectProfile(machineProfileId)
    }

    // ── Delete ──────────────────────────────────────────────────

    /** Delete from local DB + best-effort from machine via REST. */
    suspend fun deleteProfile(localProfileId: String) {
        val entity = localRepo.getProfileById(localProfileId) ?: return
        localRepo.deleteProfile(localProfileId)
        val machineId = entity.machineProfileId
        if (machineId != null && entity.syncStatus != SyncStatus.LOCAL_ONLY) {
            kotlinx.coroutines.coroutineScope {
                machineRepo.deleteProfile(machineId.toIntOrNull() ?: return@coroutineScope)
            }
        }
    }

    // ── Import ──────────────────────────────────────────────────

    /** Import profiles from a JSON string. Returns count of successfully parsed profiles. */
    suspend fun importFromJson(jsonText: String): Int {
        val profiles = parseProfilesJson(jsonText)
        profiles.forEach { localRepo.putLocalProfile(it) }
        return profiles.size
    }

    // ── Export ──────────────────────────────────────────────────

    /** Serialize a profile entity to JSON string. */
    fun exportAsJson(entity: ProfileEntity): String = gson.toJson(entity.toShotProfile())

    // ── Edit ────────────────────────────────────────────────────

    /** Save an edited profile locally + auto-upload to machine. */
    suspend fun saveEditedProfile(entity: ProfileEntity): String? {
        val updated = entity.copy(
            syncStatus = if (entity.syncStatus == SyncStatus.SYNCED) SyncStatus.MODIFIED else entity.syncStatus,
            localUpdatedAt = System.currentTimeMillis()
        )
        localRepo.saveProfile(updated)

        // Auto-upload (best-effort)
        return try {
            val profile = updated.toShotProfile()
            machineRepo.uploadProfile(profile).onSuccess {
                localRepo.markProfileSynced(updated.id, profile.profileId ?: updated.machineProfileId)
            }.onFailure { throw it }
            null // no error
        } catch (e: Exception) {
            "Saved locally (upload pending: ${e.message ?: "unknown"})"
        }
    }

    // ── Sample ─────────────────────────────────────────────────

    /** Create a default sample profile, save locally, return it. */
    suspend fun createSampleProfile(): ProfileEntity {
        val sp = ShotProfile(
            name = "Classic Espresso",
            author = "GagMate",
            notes = "A classic 9-bar espresso profile",
            phases = listOf(
                BrewPhase(name = "Preinfusion", type = "pressure", target = 3.0f, time = 8f, condition = "time", nextPhase = "Ramp"),
                BrewPhase(name = "Ramp", type = "pressure", target = 9.0f, time = 4f, condition = "time", nextPhase = "Extraction"),
                BrewPhase(name = "Extraction", type = "pressure", target = 9.0f, time = 25f, condition = "time", nextPhase = "Finish"),
                BrewPhase(name = "Finish", type = "pressure", target = 0f, time = 2f, condition = "time", nextPhase = "")
            )
        )
        val entity = ProfileEntity.fromProfile(sp)
        localRepo.saveProfile(entity)
        return entity
    }

    // ── Upload pending ─────────────────────────────────────────

    /** Upload all locally-modified profiles to the machine. */
    suspend fun uploadPending(): SyncManager.SyncResult = syncManager.uploadPendingProfiles()

    // ── Internal ───────────────────────────────────────────────

    private fun parseProfilesJson(jsonText: String): List<ShotProfile> {
        return try {
            listOfNotNull(gson.fromJson(jsonText, ShotProfile::class.java))
        } catch (e: Exception) {
            try {
                val listType = object : TypeToken<List<ShotProfile>>() {}.type
                val profiles: List<ShotProfile> = gson.fromJson(jsonText, listType)
                if (profiles.isNotEmpty()) profiles else throw Exception("Empty array")
            } catch (e2: Exception) {
                try {
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(jsonText, mapType)
                    val profileObj = map["profile"] ?: map["profiles"]
                    if (profileObj != null) {
                        try {
                            listOfNotNull(gson.fromJson(gson.toJson(profileObj), ShotProfile::class.java))
                        } catch (e3: Exception) {
                            try {
                                val innerType = object : TypeToken<List<ShotProfile>>() {}.type
                                gson.fromJson<List<ShotProfile>>(gson.toJson(profileObj), innerType)
                            } catch (e4: Exception) { emptyList() }
                        }
                    } else emptyList()
                } catch (e3: Exception) {
                    try {
                        val parts = jsonText.split(Regex("\\}\\s*\\{"))
                        if (parts.size < 2) {
                            val normalized = jsonText.replace("\r\n", "\\n").replace("\\r", "\\n")
                            val parts2 = normalized.split(Regex("}\\s*\\n+\\s*{"))
                            if (parts2.size < 2) emptyList() else parseSegments(parts2)
                        } else { parseSegments(parts) }
                    } catch (e4: Exception) { emptyList() }
                }
            }
        }
    }

    private fun parseSegments(parts: List<String>): List<ShotProfile> {
        val segments = parts.mapIndexed { index, segment ->
            when (index) {
                0 -> segment.trim() + "}"
                parts.size - 1 -> "{" + segment.trim()
                else -> "{" + segment.trim() + "}"
            }
        }
        return segments.mapNotNull { seg ->
            try { gson.fromJson(seg, ShotProfile::class.java) }
            catch (_: Exception) { null }
        }
    }
}

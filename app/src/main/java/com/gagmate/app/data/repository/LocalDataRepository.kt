package com.gagmate.app.data.repository

import com.gagmate.app.data.local.AppDatabase
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.local.entity.MachineSettingsEntity
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.model.ShotRecord
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Single entry point for all local-database operations.
 * ViewModels talk to this class; it in turn talks to Room DAOs.
 */
class LocalDataRepository(private val db: AppDatabase) {

    private val profileDao = db.profileDao()
    private val shotDao = db.shotDao()
    private val settingsDao = db.machineSettingsDao()

    // ── Profiles ─────────────────────────────────────────────────────

    /** Live list of all profiles. */
    val profilesFlow: Flow<List<ProfileEntity>> = profileDao.getAllFlow()

    /** Snapshot list. */
    suspend fun getAllProfiles(): List<ProfileEntity> = profileDao.getAll()

    suspend fun getProfileById(id: String): ProfileEntity? = profileDao.getById(id)

    /** Live, auto-updating single profile (e.g. for the detail dialog). */
    fun observeProfile(id: String): Flow<ProfileEntity?> = profileDao.observeById(id)

    suspend fun getProfileByMachineId(machineId: String): ProfileEntity? =
        profileDao.getByMachineId(machineId)

    /** Save (insert or replace) a profile. */
    suspend fun saveProfile(profile: ProfileEntity) = profileDao.upsert(profile)

    suspend fun saveProfiles(profiles: List<ProfileEntity>) = profileDao.upsertAll(profiles)

    /** Delete a local profile. If it was synced, we also delete from the machine later. */
    suspend fun deleteProfile(id: String) = profileDao.deleteById(id)

    /** Create or update a local-only profile from a ShotProfile. */
    suspend fun putLocalProfile(profile: ShotProfile): ProfileEntity {
        val existing = profile.profileId?.let { profileDao.getByMachineId(it) }
        if (existing != null) {
            val updated = existing.copy(
                name = profile.name,
                author = profile.author,
                notes = profile.notes,
                phasesJson = com.google.gson.Gson().toJson(profile.phases),
                syncStatus = SyncStatus.MODIFIED,
                localUpdatedAt = System.currentTimeMillis()
            )
            profileDao.upsert(updated)
            return updated
        }
        val entity = ProfileEntity.fromProfile(profile, SyncStatus.LOCAL_ONLY)
        profileDao.upsert(entity)
        return entity
    }

    /** Profiles waiting to be uploaded. */
    suspend fun getPendingUploads(): List<ProfileEntity> = profileDao.getPendingUploads()

    /** Number of pending uploads as a Flow. */
    val pendingUploadCount: Flow<Int> = profileDao.pendingUploadCountFlow()

    /** Mark a profile as synced after successful upload. */
    suspend fun markProfileSynced(localId: String, machineId: String?) {
        if (machineId != null) {
            profileDao.updateSyncStatusAndMachineId(localId, SyncStatus.SYNCED, machineId)
        } else {
            profileDao.updateSyncStatus(localId, SyncStatus.SYNCED)
        }
    }

    // ── Shot history ────────────────────────────────────────────────

    val shotsFlow: Flow<List<ShotEntity>> = shotDao.getAllFlow()

    suspend fun getAllShots(): List<ShotEntity> = shotDao.getAll()

    suspend fun saveShot(shot: ShotEntity) = shotDao.upsert(shot)

    suspend fun saveShots(shots: List<ShotEntity>) = shotDao.upsertAll(shots)

    suspend fun getShotById(id: String): ShotEntity? = shotDao.getById(id)

    /** IDs of shots already stored locally — lets sync skip re-downloads. */
    suspend fun getExistingShotIds(): List<String> = shotDao.getExistingIds()

    suspend fun deleteShot(id: String) = shotDao.deleteById(id)

    // ── Machine settings ────────────────────────────────────────────

    suspend fun getMachineSettings(): List<MachineSettingsEntity> = settingsDao.getAll()

    suspend fun saveMachineSettings(settings: List<MachineSettingsEntity>) =
        settingsDao.upsertAll(settings)

    // ── Clear ───────────────────────────────────────────────────────

    suspend fun clearAll() {
        profileDao.deleteAll()
        shotDao.deleteAll()
        settingsDao.deleteAll()
    }
}

package com.gagmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name ASC")
    fun getAllFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY name ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeById(id: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE machine_profile_id = :machineId")
    suspend fun getByMachineId(machineId: String): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM profiles WHERE sync_status = 'LOCAL_ONLY' OR sync_status = 'MODIFIED'")
    suspend fun getPendingUploads(): List<ProfileEntity>

    @Query("SELECT COUNT(*) FROM profiles WHERE sync_status = 'LOCAL_ONLY' OR sync_status = 'MODIFIED'")
    fun pendingUploadCountFlow(): Flow<Int>

    @Query("UPDATE profiles SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: SyncStatus)

    @Query("UPDATE profiles SET sync_status = :status, machine_profile_id = :machineId WHERE id = :id")
    suspend fun updateSyncStatusAndMachineId(id: String, status: SyncStatus, machineId: String)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

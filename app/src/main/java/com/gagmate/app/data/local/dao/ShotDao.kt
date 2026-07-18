package com.gagmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gagmate.app.data.local.entity.ShotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShotDao {
    @Query("SELECT * FROM shot_records ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<ShotEntity>>

    @Query("SELECT * FROM shot_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<ShotEntity>

    @Query("SELECT * FROM shot_records WHERE id = :id")
    suspend fun getById(id: String): ShotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shot: ShotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shots: List<ShotEntity>)

    @Query("DELETE FROM shot_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shot_records")
    suspend fun deleteAll()
}

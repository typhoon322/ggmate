package com.gagmate.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gagmate.app.data.local.entity.MachineSettingsEntity

@Dao
interface MachineSettingsDao {
    @Query("SELECT * FROM machine_settings")
    suspend fun getAll(): List<MachineSettingsEntity>

    @Query("SELECT value FROM machine_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: MachineSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(settings: List<MachineSettingsEntity>)

    @Query("DELETE FROM machine_settings")
    suspend fun deleteAll()
}

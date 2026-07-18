package com.gagmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "machine_settings")
data class MachineSettingsEntity(
    @PrimaryKey val key: String,
    val value: String = "",
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED
)

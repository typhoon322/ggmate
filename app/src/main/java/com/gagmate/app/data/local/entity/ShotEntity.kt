package com.gagmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gagmate.app.data.model.ShotRecord
import com.gagmate.app.data.model.ShotDataPoint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "shot_records")
data class ShotEntity(
    @PrimaryKey val id: String,
    val timestamp: Long = 0L,
    @ColumnInfo(name = "profile_name")
    val profileName: String = "",
    val duration: Float = 0f,
    val volume: Float = 0f,
    @ColumnInfo(name = "data_json")
    val dataJson: String = "[]",
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED
) {
    companion object {
        private val gson = Gson()

        fun fromShotRecord(record: ShotRecord): ShotEntity {
            return ShotEntity(
                id = record.id,
                timestamp = record.timestamp,
                profileName = record.profile,
                duration = record.duration,
                volume = record.volume,
                dataJson = gson.toJson(record.data),
                syncStatus = SyncStatus.SYNCED
            )
        }
    }

    fun toShotRecord(): ShotRecord {
        val type = object : TypeToken<List<ShotDataPoint>>() {}.type
        val data: List<ShotDataPoint> = try {
            gson.fromJson(dataJson, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        return ShotRecord(
            id = id,
            timestamp = timestamp,
            profile = profileName,
            duration = duration,
            volume = volume,
            data = data
        )
    }
}

package com.gagmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotDataPoint
import com.gagmate.app.data.model.ShotRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

@Entity(tableName = "shot_records")
data class ShotEntity(
    @PrimaryKey val id: String,
    val timestamp: Long = 0L,
    @ColumnInfo(name = "profile_id")
    val profileId: String? = null,
    @ColumnInfo(name = "profile_name")
    val profileName: String = "",
    val duration: Float = 0f,
    val volume: Float = 0f,
    /**
     * Real (EASE_*) brew phases captured from the shot's embedded profile.
     * This is the only reliable curve source on this firmware (REST detail is
     * dead, WS d_prof has no curve enum), so we persist it for offline use and
     * for seeding ProfileEntity.phasesJson. Defaults to "[]".
     */
    @ColumnInfo(name = "embedded_phases_json")
    val embeddedPhasesJson: String = "[]",
    @ColumnInfo(name = "data_json")
    val dataJson: String = "[]",
    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.SYNCED
) {
    companion object {
        private val gson = Gson()
        private val phasesType = object : TypeToken<List<BrewPhase>>() {}.type
        private val dataPointsType = object : TypeToken<List<ShotDataPoint>>() {}.type

        fun fromShotRecord(record: ShotRecord): ShotEntity {
            return ShotEntity(
                id = record.id,
                timestamp = record.timestamp,
                profileId = record.profileId.takeIf { it.isNotBlank() },
                profileName = record.profile,
                duration = record.duration,
                volume = record.volume,
                embeddedPhasesJson = gson.toJson(record.embeddedPhases),
                dataJson = gson.toJson(record.data),
                syncStatus = SyncStatus.SYNCED
            )
        }
    }

    /** Parsed embedded phases (real curve types), or empty if none stored. */
    fun embeddedPhases(): List<BrewPhase> = try {
        gson.fromJson<List<BrewPhase>>(embeddedPhasesJson, phasesType) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun toShotRecord(): ShotRecord {
        val data: List<ShotDataPoint> = try {
            gson.fromJson(dataJson, dataPointsType) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        return ShotRecord(
            id = id,
            timestamp = timestamp,
            profile = profileName,
            profileId = profileId ?: "",
            duration = duration,
            volume = volume,
            embeddedPhases = embeddedPhases(),
            data = data
        )
    }
}

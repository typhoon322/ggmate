package com.gagmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.model.BrewPhase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val notes: String,

    /** The profile_ID from the machine; null for brand-new local profiles. */
    @ColumnInfo(name = "machine_profile_id")
    val machineProfileId: String? = null,

    /** JSON-serialised list of BrewPhase. */
    @ColumnInfo(name = "phases_json")
    val phasesJson: String = "[]",

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,

    @ColumnInfo(name = "local_updated_at")
    val localUpdatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "machine_updated_at")
    val machineUpdatedAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        private val gson = Gson()

        /** Create a local-only entity from a ShotProfile. */
        fun fromProfile(
            profile: ShotProfile,
            status: SyncStatus = SyncStatus.LOCAL_ONLY,
            machineId: String? = profile.profileId
        ): ProfileEntity {
            val phasesJson = gson.toJson(profile.phases)
            return ProfileEntity(
                id = machineId ?: UUID.randomUUID().toString(),
                name = profile.name,
                author = profile.author,
                notes = profile.notes,
                machineProfileId = machineId,
                phasesJson = phasesJson,
                syncStatus = status,
                localUpdatedAt = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis()
            )
        }
    }

    /** Convert back to a ShotProfile (for upload / editing). */
    fun toShotProfile(): ShotProfile {
        val type = object : TypeToken<List<BrewPhase>>() {}.type
        val phases: List<BrewPhase> = try {
            gson.fromJson(phasesJson, type) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        return ShotProfile(
            name = name,
            author = author,
            notes = notes,
            profileId = machineProfileId,
            phases = phases
        )
    }

    /** Human-readable sync badge. */
    val syncLabel: String get() = when (syncStatus) {
        SyncStatus.SYNCED -> "Synced"
        SyncStatus.LOCAL_ONLY -> "Local only"
        SyncStatus.MODIFIED -> "Modified"
        SyncStatus.CONFLICT -> "Conflict"
    }
}

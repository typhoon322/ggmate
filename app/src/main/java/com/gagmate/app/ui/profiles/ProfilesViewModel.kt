package com.gagmate.app.ui.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.SyncStatus
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.repository.MachineRepository
import com.gagmate.app.data.repository.LocalDataRepository
import com.gagmate.app.data.repository.SyncManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.util.UUID

/**
 * ViewModel for the Profiles screen.
 *
 * Primary data source is the local Room database (offline-first).
 * Sync with the machine happens in the background or on demand.
 */
class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val localRepo: LocalDataRepository = AppContainer.localRepo
    private val machineRepo = MachineRepository()
    private val syncManager: SyncManager = AppContainer.syncManager
    private val gson = Gson()

    // Profiles from local DB – always available, even offline
    private val _profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val profiles: StateFlow<List<ProfileEntity>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingUploadCount = MutableStateFlow(0)
    val pendingUploadCount: StateFlow<Int> = _pendingUploadCount.asStateFlow()

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    init {
        // Observe local DB changes reactively
        viewModelScope.launch {
            localRepo.profilesFlow.collectLatest { list ->
                _profiles.value = list
            }
        }
        // Track pending upload count
        viewModelScope.launch {
            localRepo.pendingUploadCount.collectLatest { count ->
                _pendingUploadCount.value = count
            }
        }
    }

    // ── Load / sync ────────────────────────────────────────────────

    /**
     * Load profiles from the local DB (instant) then trigger a background sync.
     */
    fun loadProfiles() {
        viewModelScope.launch {
            _isLoading.value = true
            // Local data is already reactive via init{} – just trigger sync
            syncProfiles()
            _isLoading.value = false
        }
    }

    /**
     * Sync profiles with the machine (bidirectional).
     */
    fun syncProfiles() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            val result = syncManager.fullSync()
            _isSyncing.value = false

            if (result.errors.isNotEmpty()) {
                _error.value = result.errors.joinToString("; ")
            } else if (result.profilesAdded > 0 || result.profilesConflicted > 0 || result.profilesUploaded > 0) {
                _syncMessage.value = appSyncSummary(result)
            }
        }
    }

    /**
     * Upload local-only and modified profiles to the machine.
     */
    fun uploadPending() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            val result = syncManager.uploadPendingProfiles()
            _isSyncing.value = false

            if (result.errors.isNotEmpty()) {
                _error.value = result.errors.joinToString("; ")
            } else if (result.profilesUploaded > 0) {
                _syncMessage.value = "${result.profilesUploaded} profile(s) uploaded"
                syncProfiles()  // refresh after upload
            }
        }
    }

    // ── Import ──────────────────────────────────────────────────────

    /**
     * Import profiles from a JSON file URI. Saves to local DB (does not
     * automatically upload – user uploads later via the Upload button).
     */
    fun importProfileFromJson(context: Context, uri: Uri): Int {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonText = reader.readText()
            reader.close()
            inputStream?.close()

            val shotProfiles = parseProfilesJson(jsonText)
            val count = shotProfiles.size
            viewModelScope.launch {
                shotProfiles.forEach { sp -> localRepo.putLocalProfile(sp) }
            }
            count
        } catch (e: Exception) {
            _error.value = appString(R.string.profiles_import_failed) + ": ${e.message}"
            0
        }
    }

    /**
     * Import a profile from a raw JSON string (paste).
     */
    fun importProfileFromJsonString(context: Context, jsonText: String): Int {
        return try {
            val shotProfiles = parseProfilesJson(jsonText)
            val count = shotProfiles.size
            viewModelScope.launch {
                shotProfiles.forEach { sp -> localRepo.putLocalProfile(sp) }
            }
            count
        } catch (e: Exception) {
            _error.value = appString(R.string.profiles_import_failed) + ": ${e.message}"
            0
        }
    }

    /**
     * Parse JSON string into a ShotProfile.
     * Handles both single profile objects and arrays.
     */
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
                            val normalized = jsonText.replace("\\r\\n", "\\n").replace("\\r", "\\n")
                            val parts2 = normalized.split(Regex("\\}\\s*\\\\n+\\s*\\{"))
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
            catch (e: Exception) { null }
        }
    }

    // ── Delete ──────────────────────────────────────────────────────

    /**
     * Delete a profile. Removes from local DB. If it was previously synced,
     * also attempts deletion from the machine (best-effort).
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val entity = localRepo.getProfileById(profileId)
            if (entity == null) return@launch

            // Remove from local DB first
            localRepo.deleteProfile(profileId)

            // Best-effort removal from machine via profile-select endpoint
            val machineId = entity.machineProfileId
            if (machineId != null && entity.syncStatus != SyncStatus.LOCAL_ONLY) {
                // v3 uses DELETE /api/profile-select/{id}
                machineRepo.deleteProfile(machineId.toIntOrNull() ?: return@launch)
            }
        }
    }

    // ── Export ──────────────────────────────────────────────────────

    /**
     * Export a profile as JSON text (uses ShotProfile format).
     */
    fun exportProfileAsJson(entity: ProfileEntity): String {
        return gson.toJson(entity.toShotProfile())
    }

    /**
     * Create a sample profile and save it locally.
     */
    fun createSampleProfile(): ProfileEntity {
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
        viewModelScope.launch { localRepo.saveProfile(entity) }
        return entity
    }

    // ── Edit ────────────────────────────────────────────────────────

    /**
     * Save an edited profile back to the local DB (marks as MODIFIED).
     * Must be called after the user taps Save in the edit dialog.
     */
    fun saveEditedProfile(entity: ProfileEntity) {
        viewModelScope.launch {
            val updated = entity.copy(
                syncStatus = if (entity.syncStatus == SyncStatus.SYNCED) SyncStatus.MODIFIED else entity.syncStatus,
                localUpdatedAt = System.currentTimeMillis()
            )
            localRepo.saveProfile(updated)

            // Auto-upload to machine in background (best-effort)
            try {
                val profile = updated.toShotProfile()
                machineRepo.uploadProfile(profile).onSuccess {
                    localRepo.markProfileSynced(updated.id, profile.profileId ?: updated.machineProfileId)
                    _syncMessage.value = "Profile saved and uploaded"
                }.onFailure { e ->
                    _syncMessage.value = "Saved locally (upload pending: ${e.message ?: "unknown"})"
                }
            } catch (e: Exception) {
                _syncMessage.value = "Saved locally (upload pending: ${e.message ?: "unknown"})"
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun appSyncSummary(result: SyncManager.SyncResult): String {
        val parts = mutableListOf<String>()
        if (result.profilesAdded > 0) parts.add("+${result.profilesAdded} downloaded")
        if (result.profilesConflicted > 0) parts.add("${result.profilesConflicted} conflict(s)")
        if (result.profilesUploaded > 0) parts.add("${result.profilesUploaded} uploaded")
        return parts.joinToString(", ").ifEmpty { "Synced" }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    fun clearError() {
        _error.value = null
    }
}

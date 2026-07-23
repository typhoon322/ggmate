package com.gagmate.app.ui.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ViewModel for the Profiles screen.
 *
 * Business logic is delegated to [ProfileRepository].
 * This ViewModel manages UI state (loading, errors, sync messages) only.
 */
class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val profileRepo = AppContainer.profileRepo

    private val _profiles = MutableStateFlow<List<ProfileEntity>>(emptyList())
    val profiles: StateFlow<List<ProfileEntity>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingUploadCount = MutableStateFlow(0)
    val pendingUploadCount: StateFlow<Int> = _pendingUploadCount.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepo.profilesFlow.collectLatest { list ->
                _profiles.value = list
            }
        }
        viewModelScope.launch {
            profileRepo.pendingUploadCount.collectLatest { count ->
                _pendingUploadCount.value = count
            }
        }
    }

    // ── Actions ──────────────────────────────────────────────────

    fun loadProfiles() {
        // Local data already flows via profilesFlow in init{} and is shown immediately.
        // The network sync runs in the background and only updates the UI when new
        // data actually arrives (see _syncMessage), so the list never blocks on loading.
        _isSyncing.value = true
        viewModelScope.launch {
            val result = profileRepo.syncFromMachine()
            _isSyncing.value = false
            if (result.errors.isNotEmpty()) {
                _error.value = result.errors.joinToString("; ")
            } else if (result.profilesAdded > 0 || result.profilesConflicted > 0 || result.profilesUploaded > 0) {
                _syncMessage.value = syncSummary(result)
            }
        }
    }

    fun selectProfile(machineProfileId: Int) {
        try {
            profileRepo.activateProfile(machineProfileId)
            _syncMessage.value = "Profile activated"
        } catch (e: Exception) {
            _error.value = "Failed to activate profile: ${e.message}"
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val entity = profileRepo.getProfileById(profileId) ?: return@launch
            profileRepo.deleteProfile(profileId)
        }
    }

    // ── Import ──────────────────────────────────────────────────

    fun importProfileFromJson(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val jsonText = reader.readText()
                reader.close(); inputStream?.close()
                profileRepo.importFromJson(jsonText)
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            }
        }
    }

    fun importProfileFromJsonString(jsonText: String) {
        viewModelScope.launch {
            try {
                profileRepo.importFromJson(jsonText)
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            }
        }
    }

    // ── Export ──────────────────────────────────────────────────

    fun exportProfileAsJson(entity: ProfileEntity): String =
        profileRepo.exportAsJson(entity)

    // ── Save ───────────────────────────────────────────────────

    /** Push to machine first; persist locally only after the machine confirms. */
    suspend fun pushAndSaveIfConfirmed(entity: ProfileEntity): Result<Unit> =
        profileRepo.pushAndSaveIfConfirmed(entity)

    fun saveEditedProfile(entity: ProfileEntity) {
        viewModelScope.launch {
            val msg = profileRepo.saveEditedProfile(entity)
            _syncMessage.value = msg ?: "Profile saved and uploaded"
        }
    }

    // ── Sample ─────────────────────────────────────────────────

    fun createSampleProfile(): ProfileEntity {
        val sp = com.gagmate.app.data.model.ShotProfile(
            name = "Classic Espresso",
            author = "GagMate",
            notes = "A classic 9-bar espresso profile",
            phases = listOf(
                com.gagmate.app.data.model.BrewPhase(name = "Preinfusion", type = "pressure", target = 3.0f, time = 8f, condition = "time", nextPhase = "Ramp"),
                com.gagmate.app.data.model.BrewPhase(name = "Ramp", type = "pressure", target = 9.0f, time = 4f, condition = "time", nextPhase = "Extraction"),
                com.gagmate.app.data.model.BrewPhase(name = "Extraction", type = "pressure", target = 9.0f, time = 25f, condition = "time", nextPhase = "Finish"),
                com.gagmate.app.data.model.BrewPhase(name = "Finish", type = "pressure", target = 0f, time = 2f, condition = "time", nextPhase = "")
            )
        )
        val entity = com.gagmate.app.data.local.entity.ProfileEntity.fromProfile(sp)
        viewModelScope.launch { AppContainer.localRepo.saveProfile(entity) }
        return entity
    }

    // ── Upload ─────────────────────────────────────────────────

    fun uploadPending() {
        viewModelScope.launch {
            _isSyncing.value = true
            _error.value = null
            val result = profileRepo.uploadPending()
            _isSyncing.value = false
            if (result.errors.isNotEmpty()) {
                _error.value = result.errors.joinToString("; ")
            } else if (result.profilesUploaded > 0) {
                _syncMessage.value = "${result.profilesUploaded} profile(s) uploaded"
                loadProfiles()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun syncSummary(result: com.gagmate.app.data.repository.SyncManager.SyncResult): String {
        val parts = mutableListOf<String>()
        if (result.profilesAdded > 0) parts.add("+${result.profilesAdded} downloaded")
        if (result.profilesConflicted > 0) parts.add("${result.profilesConflicted} conflict(s)")
        if (result.profilesUploaded > 0) parts.add("${result.profilesUploaded} uploaded")
        return parts.joinToString(", ").ifEmpty { "Synced" }
    }

    fun clearSyncMessage() { _syncMessage.value = null }
    fun clearError() { _error.value = null }

    /** Live, auto-updating single profile — used by the detail dialog so that
     *  phases fetched over WebSocket (written back to Room) appear instantly. */
    fun profileFlow(id: String) = profileRepo.observeProfile(id)
}

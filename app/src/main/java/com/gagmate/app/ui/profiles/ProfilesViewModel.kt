package com.gagmate.app.ui.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.repository.MachineRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File

/**
 * ViewModel for the Profiles screen.
 * Manages profile listing, JSON import, and machine sync.
 */
class ProfilesViewModel : ViewModel() {

    private val repository = MachineRepository()
    private val gson = Gson()

    private val _profiles = MutableStateFlow<List<ShotProfile>>(emptyList())
    val profiles: StateFlow<List<ShotProfile>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String?>(null)
    val activeProfileId: StateFlow<String?> = _activeProfileId.asStateFlow()

    fun loadProfiles() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getProfiles().fold(
                onSuccess = { response ->
                    _profiles.value = response.profiles
                    _activeProfileId.value = response.activeProfile
                    _error.value = null
                },
                onFailure = { e ->
                    _error.value = "Failed to load profiles: ${e.message}"
                }
            )
            _isLoading.value = false
        }
    }

    /**
     * Import a profile from a JSON file URI.
     * Supports single profile objects and arrays of profiles.
     */
    fun importProfileFromJson(context: Context, uri: Uri): ShotProfile? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonText = reader.readText()
            reader.close()
            inputStream?.close()

            val profile = parseProfileJson(jsonText)
            if (profile != null) {
                // Add to local list and try to upload to machine
                _profiles.value = _profiles.value + profile
                uploadProfile(profile)
            }
            profile
        } catch (e: Exception) {
            _error.value = "Failed to import profile: ${e.message}"
            null
        }
    }

    /**
     * Import a profile from a raw JSON string (paste).
     * Saves it to a temporary file in cache, then parses and imports.
     */
    fun importProfileFromJsonString(context: Context, jsonText: String): ShotProfile? {
        return try {
            val profile = parseProfileJson(jsonText)
            if (profile != null) {
                val file = File(context.cacheDir, "pasted_profile_${System.currentTimeMillis()}.json")
                file.writeText(jsonText)
                _profiles.value = _profiles.value + profile
                uploadProfile(profile)
            }
            profile
        } catch (e: Exception) {
            _error.value = "Failed to import pasted JSON: ${e.message}"
            null
        }
    }

    /**
     * Parse JSON string into a ShotProfile.
     * Handles both single profile objects and arrays.
     */
    private fun parseProfileJson(jsonText: String): ShotProfile? {
        return try {
            gson.fromJson(jsonText, ShotProfile::class.java)
        } catch (e: Exception) {
            try {
                // Try as array
                val listType = object : TypeToken<List<ShotProfile>>() {}.type
                val profiles: List<ShotProfile> = gson.fromJson(jsonText, listType)
                profiles.firstOrNull()
            } catch (e2: Exception) {
                // Try as a map with "profile" key
                try {
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(jsonText, mapType)
                    val profileObj = map["profile"] ?: map["profiles"]
                    if (profileObj != null) {
                        gson.fromJson(gson.toJson(profileObj), ShotProfile::class.java)
                    } else null
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Upload profile to the machine.
     */
    private fun uploadProfile(profile: ShotProfile) {
        viewModelScope.launch {
            repository.uploadProfile(profile).fold(
                onSuccess = { _ ->
                    _error.value = null
                    loadProfiles()
                },
                onFailure = { e ->
                    _error.value = "Profile saved locally, upload failed: ${e.message}"
                }
            )
        }
    }

    /**
     * Delete a profile from the machine.
     */
    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId).fold(
                onSuccess = { loadProfiles() },
                onFailure = { e ->
                    _error.value = "Failed to delete: ${e.message}"
                }
            )
        }
    }

    /**
     * Export a profile as JSON text.
     */
    fun exportProfileAsJson(profile: ShotProfile): String {
        return gson.toJson(profile)
    }

    /**
     * Create a sample profile for testing.
     */
    fun createSampleProfile(): ShotProfile {
        return ShotProfile(
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
    }
}

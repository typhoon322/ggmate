package com.gagmate.app.ui.profiles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.R
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
class ProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MachineRepository()
    private val gson = Gson()

    private val _profiles = MutableStateFlow<List<ShotProfile>>(emptyList())
    val profiles: StateFlow<List<ShotProfile>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private fun appString(resId: Int, vararg args: Any?): String {
        return getApplication<Application>().getString(resId, *args)
    }

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
                    _error.value = appString(R.string.profiles_load_failed, e.message ?: "")
                }
            )
            _isLoading.value = false
        }
    }

    /**
     * Import a profile from a JSON file URI.
     * Supports single profile objects and arrays of profiles.
     */
    fun importProfileFromJson(context: Context, uri: Uri): Int {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonText = reader.readText()
            reader.close()
            inputStream?.close()

            val profiles = parseProfilesJson(jsonText)
            profiles.forEach { profile ->
                val filename = "imported_${profile.name.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "")}_${System.currentTimeMillis()}.json"
                File(context.cacheDir, filename).writeText(gson.toJson(profile))
                _profiles.value = _profiles.value + profile
                uploadProfile(profile)
            }
            profiles.size
        } catch (e: Exception) {
            _error.value = appString(R.string.profiles_import_failed) + ": ${e.message}"
            0
        }
    }

    /**
     * Import a profile from a raw JSON string (paste).
     * Saves it to a temporary file in cache, then parses and imports.
     */
    fun importProfileFromJsonString(context: Context, jsonText: String): Int {
        return try {
            val profiles = parseProfilesJson(jsonText)
            profiles.forEach { profile ->
                val filename = "pasted_${profile.name.take(20).replace(Regex("[^a-zA-Z0-9_-]"), "")}_${System.currentTimeMillis()}.json"
                File(context.cacheDir, filename).writeText(gson.toJson(profile))
                _profiles.value = _profiles.value + profile
                uploadProfile(profile)
            }
            profiles.size
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
            // Single profile object
            listOfNotNull(gson.fromJson(jsonText, ShotProfile::class.java))
        } catch (e: Exception) {
            try {
                // Array of profiles
                val listType = object : TypeToken<List<ShotProfile>>() {}.type
                val profiles: List<ShotProfile> = gson.fromJson(jsonText, listType)
                if (profiles.isNotEmpty()) profiles else throw Exception("Empty array")
            } catch (e2: Exception) {
                try {
                    // Wrapped in "profile" or "profiles" key as single object
                    val mapType = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = gson.fromJson(jsonText, mapType)
                    val profileObj = map["profile"] ?: map["profiles"]
                    if (profileObj != null) {
                        // Could be a single wrapped profile or a list
                        try {
                            listOfNotNull(gson.fromJson(gson.toJson(profileObj), ShotProfile::class.java))
                        } catch (e3: Exception) {
                            try {
                                val innerType = object : TypeToken<List<ShotProfile>>() {}.type
                                gson.fromJson<List<ShotProfile>>(gson.toJson(profileObj), innerType)
                            } catch (e4: Exception) {
                                emptyList()
                            }
                        }
                    } else emptyList()

                } catch (e3: Exception) {
                    // Try concatenated JSON objects: {...}{...}
                    try {
                        val parts = jsonText.split(Regex("\\}\\s*\\{"))
                        if (parts.size < 2) {
                            val normalized = jsonText.replace("\\r\\n", "\\n").replace("\\r", "\\n")
                            val parts2 = normalized.split(Regex("\\}\\s*\\\\n+\\s*\\{"))
                            if (parts2.size < 2) emptyList() else parseSegments(parts2)
                        } else {
                            parseSegments(parts)
                        }
                    } catch (e4: Exception) {
                        emptyList()
                    }
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


    /**
     * Backward-compatible single-profile parser.
     */
    private fun parseProfileJson(jsonText: String): ShotProfile? {
        return parseProfilesJson(jsonText).firstOrNull()
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
                    _error.value = appString(R.string.profiles_upload_failed, e.message ?: "")
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
                    _error.value = "Delete: ${e.message}"
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

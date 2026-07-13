package com.gagmate.app.data.repository

import com.gagmate.app.data.api.GgboardApiClient
import com.gagmate.app.data.model.MachineState
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.data.model.ProfilesResponse
import com.gagmate.app.data.api.ShotRecord

/**
 * Repository for accessing Gagguino machine data.
 * Abstracts the API layer and handles errors gracefully.
 */
class MachineRepository {

    private val api get() = GgboardApiClient.getApi()

    /**
     * Fetch current machine state from ggboard.
     */
    suspend fun getMachineState(): Result<MachineState> = runCatching {
        val response = api.getMachineState()
        if (response.isSuccessful) {
            response.body() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Fetch all saved profiles from ggboard.
     */
    suspend fun getProfiles(): Result<ProfilesResponse> = runCatching {
        val response = api.getProfiles()
        if (response.isSuccessful) {
            response.body() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Get active profile details.
     */
    suspend fun getActiveProfile(): Result<ShotProfile> = runCatching {
        val response = api.getActiveProfile()
        if (response.isSuccessful) {
            response.body() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Upload a profile to ggboard.
     */
    suspend fun uploadProfile(profile: ShotProfile): Result<String> = runCatching {
        val response = api.uploadProfile(profile)
        if (response.isSuccessful) {
            val body = response.body()
            body?.message ?: "Profile uploaded successfully"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Delete a profile from ggboard.
     */
    suspend fun deleteProfile(profileId: String): Result<String> = runCatching {
        val response = api.deleteProfile(profileId)
        if (response.isSuccessful) {
            val body = response.body()
            body?.message ?: "Profile deleted successfully"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Start a brew shot.
     */
    suspend fun startBrew(): Result<String> = runCatching {
        val response = api.startBrew()
        if (response.isSuccessful) {
            response.body()?.message ?: "Brew started"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Stop the current brew.
     */
    suspend fun stopBrew(): Result<String> = runCatching {
        val response = api.stopBrew()
        if (response.isSuccessful) {
            response.body()?.message ?: "Brew stopped"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Update the ggboard base URL for connection.
     */

    /**
     * Prime the pump (fill the system with water).
     */
    suspend fun primePump(): Result<String> = runCatching {
        val response = api.primePump()
        if (response.isSuccessful) {
            response.body()?.message ?: "Pump primed"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Get shot history from the machine.
     */
    suspend fun getShotHistory(): Result<List<ShotRecord>> = runCatching {
        val response = api.getShotHistory()
        if (response.isSuccessful) {
            response.body() ?: emptyList()
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Get detailed shot data for replay.
     */
    suspend fun getShotDetail(shotId: String): Result<ShotRecord> = runCatching {
        val response = api.getShotDetail(shotId)
        if (response.isSuccessful) {
            response.body() ?: throw Exception("Empty response")
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Delete a shot from history.
     */
    suspend fun deleteShotHistory(shotId: String): Result<String> = runCatching {
        val response = api.deleteShotHistory(shotId)
        if (response.isSuccessful) {
            response.body()?.message ?: "Shot deleted"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    fun updateConnection(host: String, port: Int = 80) {
        val baseUrl = "http://$host:${port}"
        GgboardApiClient.updateBaseUrl(baseUrl)
    }

    /**
     * Run a flush cycle (pump water through group head without brewing).
     */
    suspend fun flush(): Result<String> = runCatching {
        val response = api.sendCommand(mapOf("cmd" to "flush"))
        if (response.isSuccessful) {
            response.body()?.message ?: "Flush started"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Toggle steam on or off.
     */
    suspend fun toggleSteam(on: Boolean): Result<String> = runCatching {
        val response = api.sendCommand(mapOf("cmd" to "steam", "value" to if (on) "1" else "0"))
        if (response.isSuccessful) {
            response.body()?.message ?: if (on) "Steam on" else "Steam off"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

    /**
     * Set boiler temperature setpoint.
     */
    suspend fun setSetpoint(temperature: Float): Result<String> = runCatching {
        val response = api.sendCommand(mapOf("cmd" to "setpoint", "value" to temperature.toString()))
        if (response.isSuccessful) {
            response.body()?.message ?: "Setpoint updated"
        } else {
            throw Exception("HTTP ${response.code()}: ${response.errorBody()?.string()}")
        }
    }

}

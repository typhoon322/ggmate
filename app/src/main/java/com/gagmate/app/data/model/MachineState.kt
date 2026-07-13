package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Represents the real-time state of the Gagguino machine from ggboard API.
 * Maps to the /api/state JSON response.
 */
data class MachineState(
    @SerializedName("status")
    val status: String = "idle",

    @SerializedName("mode")
    val mode: String = "normal",

    @SerializedName("setpoint")
    val setpoint: Float = 0f,

    @SerializedName("temperature")
    val temperature: Float = 0f,

    @SerializedName("steam_temp")
    val steamTemperature: Float = 0f,

    @SerializedName("pressure")
    val pressure: Float = 0f,

    @SerializedName("pressure_target")
    val pressureTarget: Float = 0f,

    @SerializedName("flow")
    val flow: Float = 0f,

    @SerializedName("flow_target")
    val flowTarget: Float = 0f,

    @SerializedName("brew_time")
    val brewTime: Float = 0f,

    @SerializedName("shot_volume")
    val shotVolume: Float = 0f,

    @SerializedName("pump_output")
    val pumpOutput: Float = 0f,

    @SerializedName("steam_status")
    val steamStatus: String = "off",

    @SerializedName("profile_name")
    val activeProfileName: String = "",

    @SerializedName("phase_name")
    val currentPhaseName: String = "",

    @SerializedName("shot_number")
    val shotNumber: Int = 0,

    @SerializedName("uptime")
    val uptime: Long = 0
) {
    val isBrewing: Boolean get() = status == "brew"
    val isPreinfusion: Boolean get() = status == "preinfusion"
    val isIdle: Boolean get() = status == "idle"
    val isActive: Boolean get() = !isIdle
    val brewTimeFormatted: String get() {
        val mins = (brewTime / 60).toInt()
        val secs = (brewTime % 60).toInt()
        return if (mins > 0) "${mins}m${secs}s" else "${secs}s"
    }
}

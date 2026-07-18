package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * A single snapshot from GET /api/system/status.
 *
 * The API returns a JSON ARRAY wrapping one state object,
 * with **all values as strings**:
 * ```json
 * [{
 *   "upTime":"8763",
 *   "profileId":"24",
 *   "profileName":"Light 74158 v3.2",
 *   "targetTemperature":"95.000000",
 *   "temperature":"28.061331",
 *   "pressure":"-0.060986",
 *   "waterLevel":"100",
 *   "weight":"0.000000",
 *   "brewSwitchState":"false",
 *   "steamSwitchState":"false"
 * }]
 * ```
 */
data class MachineState(
    @SerializedName("upTime")
    val upTime: String = "0",

    @SerializedName("profileId")
    val profileId: String = "",

    @SerializedName("profileName")
    val profileName: String = "",

    @SerializedName("targetTemperature")
    val targetTemperatureStr: String = "0",

    @SerializedName("temperature")
    val temperatureStr: String = "0",

    @SerializedName("pressure")
    val pressureStr: String = "0",

    @SerializedName("waterLevel")
    val waterLevel: String = "0",

    @SerializedName("weight")
    val weight: String = "0",

    @SerializedName("brewSwitchState")
    val brewSwitchState: String = "false",

    @SerializedName("steamSwitchState")
    val steamSwitchState: String = "false"
) {
    // ── Parsed convenience accessors ──

    val temperature: Float get() = temperatureStr.toFloatOrNull() ?: 0f
    val pressure: Float  get() = pressureStr.toFloatOrNull() ?: 0f
    val targetTemperature: Float get() = targetTemperatureStr.toFloatOrNull() ?: 0f
    val upTimeSeconds: Long get() = upTime.toLongOrNull() ?: 0L

    /** true when the brew switch is pressed (machine is actively brewing). */
    val isBrewing: Boolean get() = brewSwitchState == "true"

    /** Steam switch is a physical toggle; this reflects its current position. */
    val steamOn: Boolean get() = steamSwitchState == "true"

    val isIdle: Boolean get() = !isBrewing
    val isActive: Boolean get() = isBrewing
    val setpoint: Float get() = targetTemperature

    /** Human-readable uptime. */
    val upTimeFormatted: String get() {
        val secs = upTimeSeconds
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}

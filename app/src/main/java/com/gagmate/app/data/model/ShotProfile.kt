package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * A complete Gagguino shot profile with metadata and brew phases.
 * Compatible with standard Gagguino JSON profile format for import/export.
 */
data class ShotProfile(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("author")
    val author: String = "",

    @SerializedName("notes")
    val notes: String = "",

    @SerializedName("profile_ID")
    val profileId: String? = null,

    @SerializedName("phases")
    val phases: List<BrewPhase> = emptyList()
) {
    val totalBrewTime: Float get() = phases.sumOf { it.time.toDouble() }.toFloat()
    val phaseCount: Int get() = phases.size
}

/**
 * A single phase in a brew profile.
 */
data class BrewPhase(
    @SerializedName("name")
    val name: String = "",

    @SerializedName("type")
    val type: String = "pressure",

    @SerializedName("target")
    val target: Float = 0f,

    @SerializedName("time")
    val time: Float = 0f,

    @SerializedName("condition")
    val condition: String = "time",

    @SerializedName("next")
    val nextPhase: String = "",

    @SerializedName("sensor")
    val sensor: String? = null,

    @SerializedName("value")
    val value: Float? = null
) {
    val isPressureType: Boolean get() = type == "pressure"
    val isFlowType: Boolean get() = type == "flow"
    val timeFormatted: String get() {
        val mins = (time / 60).toInt()
        val secs = (time % 60).toInt()
        return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
    }
}

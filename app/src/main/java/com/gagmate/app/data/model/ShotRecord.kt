package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * A single data point during a brew shot (old format, kept for local DB compat).
 * When displayed the time is in deciseconds (75 = 7.5s).
 */
data class ShotDataPoint(
    val time: Float = 0f,
    val pressure: Float = 0f,
    val flow: Float = 0f,
    val temperature: Float = 0f,
    val targetPressure: Float = 0f,
    val targetFlow: Float = 0f,
    val weight: Float = 0f,
    val targetTemperature: Float = 0f
)

/**
 * A completed brew shot (old format, kept for local DB compat).
 */
data class ShotRecord(
    val id: String = "",
    val timestamp: Long = 0L,
    val profile: String = "",
    val duration: Float = 0f,
    val volume: Float = 0f,
    val bean: String = "",
    val roastDate: Long = 0L,
    val dose: Float = 0f,
    val yield: Float = 0f,
    val data: List<ShotDataPoint> = emptyList()
)

// ── Gaggiuino v3 API types ──────────────────────────────────────────

/**
 * Response from GET /api/profiles/all.
 * Each entry is just a reference; the full profile with phases is
 * only available embedded in [ShotRecordApi].
 */
data class ProfileRef(
    val id: Int = 0,
    val name: String = "",
    @SerializedName("selected") val selected: String = "false"
) {
    val isSelected: Boolean get() = selected == "true"
}

/**
 * Response from GET /api/shots/latest.
 */
data class LatestShotResponse(
    val lastShotId: String = ""
)

/**
 * Full shot record returned by GET /api/shots/{id}.
 * Uses columnar datapoints and includes the embedded profile.
 */
data class ShotRecordApi(
    val id: Int = 0,
    val timestamp: Long = 0L,
    val duration: Int = 0,
    val datapoints: ShotDataPointsApi = ShotDataPointsApi(),
    val profile: EmbeddedProfile? = null
) {
    /** duration deciseconds → seconds for display */
    val durationSec: Float get() = duration / 10f

    /** Convert to the local [ShotRecord] format for persistence. */
    fun toShotRecord(): ShotRecord {
        val count = datapoints.timeInShot.size
        val points = List(count) { i ->
            ShotDataPoint(
                time = datapoints.timeInShot.getOrElse(i) { 0 } / 10f,
                pressure = datapoints.pressure.getOrElse(i) { 0 } / 10f,
                flow = datapoints.pumpFlow.getOrElse(i) { 0 } / 10f,
                temperature = datapoints.temperature.getOrElse(i) { 0 } / 10f,
                targetPressure = datapoints.targetPressure.getOrElse(i) { 0 } / 10f,
                targetFlow = datapoints.targetPumpFlow.getOrElse(i) { 0 } / 10f,
                weight = datapoints.weightFlow.getOrElse(i) { 0 } / 10f,
                targetTemperature = datapoints.targetTemperature.getOrElse(i) { 0 } / 10f
            )
        }
        return ShotRecord(
            id = id.toString(),
            timestamp = timestamp * 1000L,  // API is seconds → millis
            profile = profile?.name ?: "",
            duration = durationSec,
            volume = (datapoints.weightFlow.lastOrNull() ?: 0) / 10f,
            data = points
        )
    }
}

/**
 * Columnar brew-shot datapoints from the API.
 * Each field is a synchronised array of integer values in
 *   « tenths of the unit »  (940 = 94.0 °C, 75 = 7.5 s, …).
 */
data class ShotDataPointsApi(
    @SerializedName("timeInShot")
    val timeInShot: List<Int> = emptyList(),

    val pressure: List<Int> = emptyList(),
    val pumpFlow: List<Int> = emptyList(),
    val weightFlow: List<Int> = emptyList(),
    val temperature: List<Int> = emptyList(),
    val targetTemperature: List<Int> = emptyList(),
    val targetPumpFlow: List<Int> = emptyList(),
    val targetPressure: List<Int> = emptyList()
)

/**
 * The full profile embedded in a shot record.
 */
data class EmbeddedProfile(
    val id: Int = 0,
    val name: String = "",
    val phases: List<PhaseV3> = emptyList(),
    val globalStopConditions: GlobalStopConditions? = null,
    val waterTemperature: Float? = null,
    val recipe: Map<String, Any>? = null
)


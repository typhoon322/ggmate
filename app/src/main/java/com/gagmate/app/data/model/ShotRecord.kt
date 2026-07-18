package com.gagmate.app.data.model

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

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
    val targetTemperature: Float = 0f,
    /** Accumulated weight in grams (from shotWeight column). */
    val shotWeight: Float = 0f
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
 *
 * The Gen3 firmware returns datapoints as an ARRAY of objects
 * (like the web UI export), NOT the columnar format.
 * We parse both formats via JsonElement.
 */
data class ShotRecordApi(
    val id: Int = 0,
    val timestamp: Long = 0L,
    val duration: Int = 0,
    val datapoints: JsonElement? = null,
    val profile: EmbeddedProfile? = null
) {
    /** duration deciseconds → seconds for display */
    val durationSec: Float get() = duration / 10f

    companion object {
        private val gson = Gson()
    }

    /** Convert to the local [ShotRecord] format for persistence. */
    fun toShotRecord(): ShotRecord {
        val points = parseDatapoints()
        val lastShotWeight = points.lastOrNull()?.shotWeight ?: 0f
        return ShotRecord(
            id = id.toString(),
            timestamp = timestamp * 1000L,
            profile = profile?.name ?: "",
            duration = durationSec,
            volume = if (lastShotWeight > 0f) lastShotWeight
                     else (points.lastOrNull()?.weight ?: 0f),
            data = points
        )
    }

    private fun parseDatapoints(): List<ShotDataPoint> {
        val dp = datapoints ?: return emptyList()

        // ── Columnar format (object with arrays of Ints in tenths) ──
        if (dp.isJsonObject) {
            val obj = gson.fromJson(dp, ShotDataPointsApi::class.java)
            if (obj.timeInShot.isEmpty()) return emptyList()
            val count = obj.timeInShot.size
            return List(count) { i ->
                ShotDataPoint(
                    time = obj.timeInShot.getOrElse(i) { 0 } / 10f,
                    pressure = obj.pressure.getOrElse(i) { 0 } / 10f,
                    flow = obj.pumpFlow.getOrElse(i) { 0 } / 10f,
                    temperature = obj.temperature.getOrElse(i) { 0 } / 10f,
                    targetPressure = obj.targetPressure.getOrElse(i) { 0 } / 10f,
                    targetFlow = obj.targetPumpFlow.getOrElse(i) { 0 } / 10f,
                    weight = obj.weightFlow.getOrElse(i) { 0 } / 10f,
                    targetTemperature = obj.targetTemperature.getOrElse(i) { 0 } / 10f,
                    shotWeight = if (obj.shotWeight.isNotEmpty()) obj.shotWeight.getOrElse(i) { 0 } / 10f else 0f
                )
            }
        }

        // ── Array format (array of objects with floats) ──
        if (dp.isJsonArray) {
            val listType = object : TypeToken<List<Map<String, Double>>>() {}.type
            val arr: List<Map<String, Double>> = gson.fromJson(dp, listType) ?: return emptyList()
            if (arr.isEmpty()) return emptyList()
            return arr.map { item ->
                fun d(key: String): Float = (item[key] ?: 0.0).toFloat()
                ShotDataPoint(
                    time = d("timeInShot") / 1000f,   // ms → seconds
                    pressure = d("pressure"),
                    flow = d("pumpFlow"),
                    temperature = d("temperature"),
                    targetPressure = d("targetPressure"),
                    targetFlow = d("targetPumpFlow"),
                    weight = d("weightFlow"),          // flow rate (g/s)
                    targetTemperature = d("targetTemperature"),
                    shotWeight = d("shotWeight")       // accumulated weight (g)
                )
            }
        }

        return emptyList()
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
    val targetPressure: List<Int> = emptyList(),
    val shotWeight: List<Int> = emptyList(),
    @SerializedName("waterPumped")
    val waterPumped: List<Int> = emptyList()
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


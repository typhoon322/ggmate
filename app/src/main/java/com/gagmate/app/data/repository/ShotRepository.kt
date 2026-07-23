package com.gagmate.app.data.repository

import com.gagmate.app.data.protocol.SensorSnapshot
import com.gagmate.app.data.protocol.ShotSnapshot
import com.gagmate.app.data.session.MachineSessionManager
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Provides live brew shot data flows for the UI layer.
 *
 * Self-manages a rolling buffer of [ChartPoint]s from the real-time
 * [ShotSnapshot] stream coming off the [MachineSessionManager] WebSocket.
 * Because the collection lives here (not in a screen-scoped ViewModel),
 * the buffer keeps filling even when the user navigates away from the
 * dashboard — e.g. into the live curve screen.
 */
class ShotRepository(
    private val session: MachineSessionManager
) {

    companion object {
        /** Maximum number of points kept in the rolling buffer. */
        private const val MAX_POINTS = 2000

        /**
         * A shot snapshot whose timeInShot is below this (raw, in whatever unit
         * the firmware sends) marks the start of a fresh shot → reset the buffer.
         */
        private const val NEW_SHOT_THRESHOLD = 100
    }

    private val _chartData = MutableStateFlow<List<ChartPoint>>(emptyList())

    /** Rolling chart data (pressure, flow, temperature, weight over time). */
    val chartData: StateFlow<List<ChartPoint>> = _chartData.asStateFlow()

    /** Latest live shot weight (accumulated grams). */
    val liveWeight: Flow<Float> = session.shotSnapshot.map { it?.weight ?: 0f }

    /** Latest shot snapshot. */
    val shotSnapshot: Flow<ShotSnapshot?> = session.shotSnapshot

    /**
     * Wall-clock timestamp (ms) at which the current brew started. Used to
     * derive a time axis for the sensor-stream backfill (see [start]).
     */
    private val shotStartWallMs = AtomicLong(0L)

    /**
     * Last `timeInShot` (raw) seen from a firmware shot snapshot — used to
     * detect a monotonic, plausible firmware time axis and to recognise a
     * genuine new shot (time jumps backwards).
     */
    private val prevShotTime = AtomicLong(-1)

    /**
     * Last time (ms) a firmware shot snapshot arrived. Used to de-duplicate
     * against the sensor-stream backfill so we don't draw the same instant
     * twice when a firmware emits both.
     */
    private val lastFirmwareShotAt = AtomicLong(0L)

    /**
     * Begin streaming shot data into [chartData]. Call once (from
     * [com.gagmate.app.data.repository.AppContainer]) with an app-lifetime scope.
     *
     * Two sources feed the buffer:
     *  1. Firmware shot snapshots (protobuf [ShotSnapshotMsg] / JSON
     *     `shot_data_update`) — authoritative, carry `timeInShot`.
     *  2. The live sensor stream ([SensorSnapshot]) *during a brew*. Some
     *     firmwares stream pressure/flow/temp/weight via `sensor_data_update`
     *     while brewing but never emit a shot snapshot, so without this
     *     backfill the live curve stays blank even though `brewActive` is true.
     *
     * A wall-clock time axis is used as the reliable fallback so the curve
     * always draws during a brew, even if the firmware's `timeInShot` is
     * absent or misread. The buffer is cleared only on a genuine brew-start
     * (brewActive rising edge) — never on a single low `timeInShot`, which
     * would otherwise empty the buffer on every frame.
     */
    fun start(scope: CoroutineScope) {
        // Detect brew start (rising edge) → reset the time axis and clear buffer.
        scope.launch {
            var wasBrewing = false
            session.brewActive.collect { active ->
                if (active && !wasBrewing) {
                    clearChart()
                    shotStartWallMs.set(System.currentTimeMillis())
                    prevShotTime.set(-1)
                }
                wasBrewing = active
            }
        }
        // 1) Authoritative shot snapshots.
        scope.launch {
            session.shotSnapshot.collect { snapshot ->
                if (snapshot == null || !session.brewActive.value) return@collect
                lastFirmwareShotAt.set(System.currentTimeMillis())
                val wallT = (System.currentTimeMillis() - shotStartWallMs.get()).coerceAtLeast(0L) / 1000f
                // Prefer the firmware time when it is monotonic & plausible;
                // otherwise fall back to the wall-clock axis so the curve still draws.
                val t = if (snapshot.timeInShot > 0 && snapshot.timeInShot > prevShotTime.get()) {
                    prevShotTime.set(snapshot.timeInShot.toLong()); snapshot.timeInShot / 1000f
                } else wallT
                appendPoint(t, snapshot.pressure, snapshot.flow, snapshot.temperature, snapshot.weight)
            }
        }
        // 2) Sensor-stream backfill while brewing.
        scope.launch {
            session.sensorSnapshot.collect { s ->
                if (!session.brewActive.value) return@collect
                val now = System.currentTimeMillis()
                // If a real shot snapshot arrived very recently, prefer it and
                // skip this sensor point to avoid a double-drawn curve.
                if (now - lastFirmwareShotAt.get() < 400) return@collect
                val t = (now - shotStartWallMs.get()).coerceAtLeast(0L) / 1000f
                appendPoint(t, s.pressure, s.pumpFlow, s.temperature, s.weight)
            }
        }
    }

    /**
     * Append a data point (from either a shot snapshot or a live sensor reading)
     * to the rolling buffer.
     */
    fun appendPoint(time: Float, pressure: Float, flow: Float, temperature: Float, weight: Float) {
        val point = ChartPoint(
            time = time,
            pressure = pressure,
            flowRate = flow,
            temperature = temperature,
            weight = weight,
            shotWeight = weight
        )
        _chartData.value = (_chartData.value + point).takeLast(MAX_POINTS)
    }

    /** @deprecated kept for call-site compatibility; use [appendPoint]. */
    fun appendShotPoint(snapshot: ShotSnapshot) =
        appendPoint(snapshot.timeInShot / 1000f, snapshot.pressure, snapshot.flow, snapshot.temperature, snapshot.weight)

    /** Clear chart data (e.g., at the start of a new shot). */
    fun clearChart() {
        _chartData.value = emptyList()
    }
}

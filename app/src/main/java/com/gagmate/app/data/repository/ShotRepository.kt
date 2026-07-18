package com.gagmate.app.data.repository

import com.gagmate.app.data.protocol.ShotSnapshot
import com.gagmate.app.data.session.MachineSessionManager
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Provides live brew shot data flows for the UI layer.
 * Maintains a rolling buffer of chart data points from the
 * real-time [ShotSnapshot] stream.
 */
class ShotRepository(
    private val session: MachineSessionManager
) {
    companion object {
        /** Maximum number of points kept in the rolling buffer. */
        private const val MAX_POINTS = 2000
    }

    private val _chartData = MutableStateFlow<List<ChartPoint>>(emptyList())

    /** Rolling chart data (pressure, flow over time). */
    val chartData: StateFlow<List<ChartPoint>> = _chartData.asStateFlow()

    /** Latest live shot weight. */
    val liveWeight: Flow<Float> = session.shotSnapshot.map { it?.weight ?: 0f }

    /** Latest shot snapshot. */
    val shotSnapshot: Flow<ShotSnapshot?> = session.shotSnapshot

    /**
     * Append a new data point from a shot snapshot to the rolling buffer.
     * Call this when a new [ShotSnapshot] arrives.
     */
    fun appendShotPoint(snapshot: ShotSnapshot) {
        val point = ChartPoint(
            time = snapshot.timeInShot / 1000f,
            pressure = snapshot.pressure,
            flowRate = snapshot.flow
        )
        _chartData.value = (_chartData.value + point).takeLast(MAX_POINTS)
    }

    /** Clear chart data (e.g., at the start of a new shot). */
    fun clearChart() {
        _chartData.value = emptyList()
    }
}

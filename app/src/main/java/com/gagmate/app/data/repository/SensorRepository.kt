package com.gagmate.app.data.repository

import com.gagmate.app.data.protocol.SensorSnapshot
import com.gagmate.app.data.session.MachineSessionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Provides processed sensor data flows for the UI layer.
 * Subscribes to [MachineSessionManager] and transforms raw data
 * into UI-friendly formats (e.g., with target temperature).
 */
class SensorRepository(
    private val session: MachineSessionManager
) {

    /** Raw sensor snapshot from the machine. */
    val sensorSnapshot: Flow<SensorSnapshot> = session.sensorSnapshot

    /** Current boiler temperature. */
    val temperature: Flow<Float> = sensorSnapshot.map { it.temperature }

    /** Current brew pressure. */
    val pressure: Flow<Float> = sensorSnapshot.map { it.pressure }

    /** Current water level (0-100). */
    val waterLevel: Flow<Int> = sensorSnapshot.map { it.waterLevel }
}

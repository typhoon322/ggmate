@file:Suppress("unused")

package com.gagmate.app.data.model



/**
 * A single brew phase in Gaggiuino v3 format.
 */
data class PhaseV3(
    val target: PhaseTarget? = null,
    val stopConditions: PhaseStopConditions? = null,
    val type: String = "",
    val skip: Boolean = false,
    val name: String = "",
    val restriction: Int? = null,
    val waterTemperature: Float? = null
)

data class PhaseStopConditions(
    val time: Int? = null,
    val pressureAbove: Float? = null,
    val pressureBelow: Float? = null,
    val flowAbove: Float? = null,
    val flowBelow: Float? = null,
    val weight: Float? = null,
    val waterPumpedInPhase: Float? = null
)

/**
 * Transition target for a brew phase.
 * Unit: bar for PRESSURE, ml/s for FLOW; time in milliseconds.
 */
data class PhaseTarget(
    val start: Float? = null,
    val end: Float = 0f,
    val curve: String = "LINEAR",
    val time: Int = 0
)

data class GlobalStopConditions(
    val time: Int? = null,
    val weight: Float? = null,
    val waterPumped: Float? = null
)

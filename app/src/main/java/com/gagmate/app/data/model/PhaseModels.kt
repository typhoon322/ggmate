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


/**
 * Minimum width (seconds) we draw a phase at when the profile declares no
 * usable duration. Some Gaggiuino profiles (e.g. "turbo shot" phases 2-3)
 * declare a flow target but stop on the global weight limit with no per-phase
 * time, so the real duration is only known once the shot is pulled. A visible
 * floor keeps the target curve from collapsing into an invisible sliver.
 */
const val MIN_PHASE_SECONDS = 4f

/**
 * Convert Gaggiuino v3 API format (PhaseV3) to local BrewPhase format
 * for storage and chart generation.
 *
 * Phase duration is resolved in priority order:
 *  1. the explicit target-curve time ([PhaseTarget.time], ms → s);
 *  2. a volume stop on a FLOW phase → estimate time ≈ volume / flow rate;
 *  3. a non-zero stop-conditions time;
 *  4. [MIN_PHASE_SECONDS] visible floor (volume/weight-driven, undefined).
 *
 * The stop condition type is preserved in [BrewPhase.condition]/[BrewPhase.value]
 * so the phase list can show it and it survives the Room round-trip.
 */
fun PhaseV3.toBrewPhase(): com.gagmate.app.data.model.BrewPhase {
    val targetMs = target?.time ?: 0
    val targetEnd = target?.end ?: 0f
    val sc = stopConditions
    val t = type.lowercase().takeIf { it in setOf("pressure", "flow") } ?: "pressure"

    val durationSec: Float = when {
        targetMs > 0 -> targetMs / 1000f
        sc?.waterPumpedInPhase != null && sc.waterPumpedInPhase > 0f && t == "flow" && targetEnd > 0f ->
            (sc.waterPumpedInPhase / targetEnd).coerceIn(MIN_PHASE_SECONDS, 120f)
        sc?.time != null && sc.time > 0 -> sc.time / 1000f
        else -> MIN_PHASE_SECONDS
    }

    val condition = when {
        sc?.waterPumpedInPhase != null && sc.waterPumpedInPhase > 0f -> "volume"
        sc?.weight != null && sc.weight > 0f -> "weight"
        sc?.pressureAbove != null || sc?.pressureBelow != null ||
            sc?.flowAbove != null || sc?.flowBelow != null -> "limit"
        else -> "time"
    }
    val value: Float? = when (condition) {
        "volume" -> sc?.waterPumpedInPhase
        "weight" -> sc?.weight
        "time" -> if (sc?.time != null && sc.time > 0) sc.time / 1000f else null
        else -> null
    }

    return com.gagmate.app.data.model.BrewPhase(
        name = name,
        type = t,
        target = targetEnd,
        start = target?.start ?: 0f,
        variation = target?.curve?.uppercase()?.takeIf { it.isNotBlank() } ?: "LINEAR",
        time = durationSec,
        condition = condition,
        value = value
    )
}

/**
 * Convert a whole profile's phases ([PhaseV3]) into [BrewPhase]s, using the
 * profile-level [GlobalStopConditions] to estimate durations for flow phases
 * that have no explicit time and simply stop on the global weight/volume limit
 * (e.g. the "turbo shot" Main Extraction / Flow Finish phases).
 *
 * Estimation (real flow-rate math, not a placeholder):
 *  - target volume `V` = `globalStopConditions.weight` (grams ≈ ml for water/espresso).
 *  - phases with an explicit time, or a per-phase `waterPumpedInPhase`, already
 *    know their poured volume → subtract those from `V` to get the *remaining*
 *    volume that the volume-driven flow phases must pour.
 *  - that remaining volume is shared across those flow phases in proportion to
 *    their flow rate; since `time = volume / flow`, every such phase ends up with
 *    the SAME duration `remaining / Σ(flowRates)`.
 *
 * Example (turbo shot): P1 pours 3.5 ml/s × 5 s = 17.5 ml; remaining 82.5 ml is
 * split by flow (5 and 2.5) → each of P2/P3 gets 82.5 / 7.5 = 11 s.
 */
fun List<PhaseV3>.toBrewPhases(global: GlobalStopConditions? = null): List<BrewPhase> {
    val base = map { it.toBrewPhase() }
    val globalVol = (global?.weight ?: global?.waterPumped)?.takeIf { it > 0f } ?: return base

    var knownVol = 0f
    val estIdx = mutableListOf<Int>()
    var sumFlow = 0f
    forEachIndexed { i, p ->
        val ms = p.target?.time ?: 0
        val end = p.target?.end ?: 0f
        val isFlow = p.type.equals("flow", ignoreCase = true)
        val perPhaseVol = p.stopConditions?.waterPumpedInPhase
        when {
            ms > 0 && isFlow && end > 0f -> knownVol += end * ms / 1000f
            perPhaseVol != null && perPhaseVol > 0f -> knownVol += perPhaseVol
            isFlow && end > 0f && ms == 0 && (perPhaseVol ?: 0f) <= 0f -> {
                estIdx += i
                sumFlow += end
            }
        }
    }
    if (estIdx.isEmpty() || sumFlow <= 0f) return base

    val remaining = maxOf(0f, globalVol - knownVol)
    val perPhaseSec = (remaining / sumFlow).coerceIn(MIN_PHASE_SECONDS, 120f)
    return base.mapIndexed { i, bp ->
        if (i in estIdx) bp.copy(time = perPhaseSec, condition = "weight", value = globalVol)
        else bp
    }
}

package com.gagmate.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.R
import com.gagmate.app.data.model.BrewPhase
import kotlin.math.sqrt

// Shared series colours (kept in sync with BrewChartView / ShotChartFullScreen).
val ChartColorPressure = Color(0xFF2196F3)   // 蓝色 pressure
val ChartColorFlow = Color(0xFFFFC107)        // 黄色 flow
val ChartColorTemperature = Color(0xFFF44336) // 红色 temperature
val ChartColorWeight = Color(0xFF8D6E63)       // 褐色 weight
val ChartColorWeightRate = Color(0xFF4CAF50)   // 绿色 weight-rate
val ChartColorTargetPressure = ChartColorPressure.copy(alpha = 0.4f)
val ChartColorTargetFlow = ChartColorFlow.copy(alpha = 0.4f)
val ChartColorTargetTemperature = ChartColorTemperature.copy(alpha = 0.4f)

/** Which Y axis a series is plotted against. */
enum class ChartAxis { LEFT, RIGHT }

/**
 * A single plotted line. [valueAt] returns the metric value for the point at
 * the given index; [axis] selects the left (0–[leftMax]) or right (0–[rightMax])
 * scale; [dashed] draws a semi-transparent target-style line. [valueAt] is the
 * last parameter so callers can pass it as a trailing lambda.
 */
data class CurveSeries(
    val color: Color,
    val axis: ChartAxis,
    val dashed: Boolean = false,
    val valueAt: (Int) -> Float
)

// ── Curve variation (change mode) interpolation ──────────────
// Mirrors the Gaggiuino PhaseTarget.curve enum. Given progress p∈[0,1] within a
// phase, return the eased progress used to interpolate start→end.
fun curveVariationEasedProgress(variation: String, p: Float): Float {
    val t = p.coerceIn(0f, 1f)
    return when (variation.uppercase()) {
        "FLAT", "LINEAR" -> t
        "EASE_IN" -> t * t                                  // slow start, fast end
        "EASE_OUT" -> 1f - sqrt(1f - t)                      // slow start, fast end (strong)
        "EASE_IN_OUT" -> t * t * (3f - 2f * t)               // smooth S-curve
        "FAST_IN" -> sqrt(t)                                 // very fast start
        "FAST_OUT" -> 1f - (1f - t) * (1f - t)               // fast start, very slow end
        "FAST_IN_OUT" -> {                                   // strong S-curve
            if (t < 0.5f) 2f * t * t else 1f - ((-2f * t + 2f).let { it * it } / 2f)
        }
        else -> t
    }
}

/**
 * Build chart points for a profile's target curve, ramping each phase from its
 * [BrewPhase.start] to [BrewPhase.target] using the phase's variation mode
 * (EASE_OUT / EASE_IN_OUT / …) instead of a flat step. The previous phase's
 * end value is carried over so neighbouring phases connect smoothly.
 */
fun generateProfileChartPoints(
    phases: List<BrewPhase>,
    resolution: Float = 0.25f
): List<ChartPoint> {
    if (phases.isEmpty()) return emptyList()
    val points = mutableListOf<ChartPoint>()
    var elapsed = 0f
    var lastPressure = 0f
    var lastFlow = 0f
    for (phase in phases) {
        val duration = phase.time.coerceAtLeast(0.1f)
        val start = if (phase.start > 0f) phase.start
        else if (phase.type == "pressure") lastPressure else lastFlow
        val end = phase.target
        val isPressure = phase.type == "pressure"
        val varc = phase.variation
        fun valueAt(progress: Float): Float {
            val eased = curveVariationEasedProgress(varc, progress)
            return start + (end - start) * eased
        }
        fun emit(time: Float) {
            val v = valueAt(time / duration)
            if (isPressure) {
                lastPressure = v
                points.add(ChartPoint(time = elapsed + time, pressure = v, flowRate = lastFlow))
            } else {
                lastFlow = v
                points.add(ChartPoint(time = elapsed + time, pressure = lastPressure, flowRate = v))
            }
        }
        var t = 0f
        while (t < duration) { emit(t); t = (t + resolution).coerceAtMost(duration) }
        emit(duration) // guarantee the phase end point is present
        elapsed += duration
    }
    return points
}

/**
 * Shared curve renderer — the single source of truth for drawing shot /
 * profile charts (axes, grid, dashed target lines, solid actual lines,
 * end-of-line markers, optional crosshair). Replaces the previously
 * duplicated Canvas code in [BrewChartView] and [ShotChartFullScreen].
 *
 * @param pointCount number of plotted points
 * @param timeAt      time (seconds) of point i
 * @param series      series to draw
 * @param leftMax     maximum of the left (pressure/flow/rate) axis
 * @param rightMax    maximum of the right (temperature/weight) axis
 * @param t0          visible window start time (seconds)
 * @param t1          visible window end time (seconds)
 * @param showMarkers draw end-of-line dots for each series
 * @param crosshairTime optional time to draw a vertical crosshair at
 */
@Composable
fun CurveChart(
    modifier: Modifier = Modifier,
    pointCount: Int,
    timeAt: (Int) -> Float,
    series: List<CurveSeries>,
    leftMax: Float = 16f,
    rightMax: Float = 100f,
    t0: Float = 0f,
    t1: Float,
    showMarkers: Boolean = true,
    crosshairTime: Float? = null
) {
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
    Canvas(modifier = modifier) {
        val plotW = size.width
        val plotH = size.height
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        // Grid (5 horizontal lines)
        for (i in 0..4) {
            val y = plotH * i / 4f
            drawLine(C_GRID, Offset(0f, y), Offset(plotW, y), 1f)
        }

        if (pointCount < 2) return@Canvas

        val tRange = (t1 - t0).coerceAtLeast(1f)
        fun xPos(time: Float): Float = ((time - t0) / tRange * plotW).toFloat()
        fun yLeft(v: Float): Float = plotH - ((v / leftMax).coerceIn(0f, 1f) * plotH)
        fun yRight(v: Float): Float = plotH - ((v / rightMax).coerceIn(0f, 1f) * plotH)

        for (s in series) {
            val path = Path()
            for (i in 0 until pointCount) {
                val x = xPos(timeAt(i))
                val y = if (s.axis == ChartAxis.LEFT) yLeft(s.valueAt(i)) else yRight(s.valueAt(i))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (!path.isEmpty) {
                drawPath(
                    path, s.color,
                    style = if (s.dashed) Stroke(2f, pathEffect = dashEffect, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    else Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        if (showMarkers && pointCount > 0) {
            val lastX = xPos(timeAt(pointCount - 1))
            for (s in series) {
                val v = s.valueAt(pointCount - 1)
                val y = if (s.axis == ChartAxis.LEFT) yLeft(v) else yRight(v)
                drawCircle(s.color, 4f, Offset(lastX, y))
            }
        }

        if (crosshairTime != null && crosshairTime >= t0 && crosshairTime <= t1) {
            val cx = xPos(crosshairTime)
            drawLine(Color(0xFF666666).copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, plotH), 2f)
        }
    }
}

/**
 * Convenience component for drawing a profile's target curve (pressure / flow
 * with the variation mode applied). Used by the dashboard "current curve" card
 * and the profile editor preview.
 */
@Composable
fun ProfileCurveChart(
    phases: List<BrewPhase>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp
) {
    val points = remember(phases) { generateProfileChartPoints(phases) }
    if (points.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.profile_no_phases),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    CurveChart(
        modifier = modifier.fillMaxWidth().height(height),
        pointCount = points.size,
        timeAt = { points[it].time },
        series = listOf(
            CurveSeries(ChartColorPressure, ChartAxis.LEFT) { points[it].pressure },
            CurveSeries(ChartColorFlow, ChartAxis.LEFT) { points[it].flowRate }
        ),
        t0 = 0f,
        t1 = points.last().time.coerceAtLeast(1f),
        showMarkers = false
    )
}

private val C_GRID = Color(0xFFD7CCC8).copy(alpha = 0.4f)

package com.gagmate.app.ui.components

import androidx.activity.ComponentActivity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.R
import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.model.ShotDataPoint
import com.gagmate.app.data.repository.AppContainer
import kotlin.math.abs

private val PRESSURE_COLOR = Color(0xFF2196F3)
private val FLOW_COLOR = Color(0xFFFFC107)
private val TEMP_COLOR = Color(0xFFF44336)
private val WEIGHT_COLOR = Color(0xFF8D6E63)
private val WRATE_COLOR = Color(0xFF4CAF50)
private val GRID_COLOR = Color(0xFFD7CCC8).copy(alpha = 0.35f)
private val AXIS_TEXT = Color(0xFF8D6E63)

/** Left axis carries pressure / flow / weight-rate (shared 0–12 scale). */
private const val LEFT_MAX = 12f
/** Right axis carries temperature / accumulated weight (shared 0–100 scale). */
private const val RIGHT_MAX = 100f

/**
 * Full-screen, landscape shot chart.
 *
 * Draws through the shared [CurveChart] renderer (axes, dashed targets, solid
 * actuals, crosshair). Supports drag-to-scrub, zoom (1×→20×) with a pan
 * slider, and auto-fits the shot's real duration. Loaded by shot id; the shot
 * is read from the local repository.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotChartFullScreen(
    shotId: String,
    onClose: () -> Unit
) {
    val shot by produceShot(shotId)
    val dataPoints = remember(shot) { shot?.toShotRecord()?.data ?: emptyList() }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        if (dataPoints.size < 2) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.history_chart_no_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        val totalTime: Float = dataPoints.last().time.coerceAtLeast(1f)

        var zoom by remember { mutableStateOf(1f) }
        var panOffset by remember { mutableStateOf(0f) }
        var touchedTime by remember { mutableStateOf(totalTime) }
        var plotSize by remember { mutableStateOf(IntSize.Zero) }

        val visibleWindow: Float = totalTime / zoom
        val maxPan: Float = (totalTime - visibleWindow).coerceAtLeast(0f)
        LaunchedEffect(zoom, totalTime) {
            panOffset = panOffset.coerceIn(0f, maxPan)
            if (zoom <= 1f) panOffset = 0f
            touchedTime = touchedTime.coerceIn(panOffset, panOffset + visibleWindow)
        }

        val hasTemp = dataPoints.any { it.temperature > 0.5f }
        val hasWeight = dataPoints.any { (if (it.shotWeight > 0f) it.shotWeight else it.weight) > 0.1f }
        val hasWRate = dataPoints.any { it.weight > 0.1f }
        val hasTarget = dataPoints.any { it.targetPressure > 0f || it.targetFlow > 0f || it.targetTemperature > 0f }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(padding)
        ) {
            // Minimal header: profile name + duration + close (replaces the tall TopAppBar).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = shot?.profileName?.ifEmpty { stringResource(R.string.history_chart_title) }
                        ?: stringResource(R.string.history_chart_title),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (dataPoints.isNotEmpty()) {
                    Text(
                        String.format("%.1fs", dataPoints.last().time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_back), modifier = Modifier.size(18.dp))
                }
            }
            // Legend
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LegendDot(stringResource(R.string.chart_pressure), PRESSURE_COLOR)
                LegendDot(stringResource(R.string.chart_pump_flow), FLOW_COLOR)
                if (hasTemp) LegendDot(stringResource(R.string.chart_temperature), TEMP_COLOR)
                if (hasWeight) LegendDot(stringResource(R.string.chart_weight), WEIGHT_COLOR)
                if (hasWRate) LegendDot(stringResource(R.string.chart_weight_rate), WRATE_COLOR)
                if (hasTarget) LegendDot(stringResource(R.string.chart_target) + " *", AXIS_TEXT, isDashed = true)
            }

            // Chart area: left axis | plot | right axis
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier.width(30.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(5) { i -> Text("${LEFT_MAX * (4 - i) / 4}", fontSize = 9.sp, color = AXIS_TEXT) }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f)
                                .onSizeChanged { plotSize = it }
                                .pointerInput(dataPoints, panOffset, visibleWindow, plotSize) {
                                    detectTapGestures(
                                        onPress = { offset -> touchedTime = timeAtX(offset.x, plotSize.width.toFloat(), panOffset, visibleWindow) },
                                        onTap = { offset -> touchedTime = timeAtX(offset.x, plotSize.width.toFloat(), panOffset, visibleWindow) }
                                    )
                                }
                                .pointerInput(dataPoints, panOffset, visibleWindow, plotSize) {
                                    detectDragGestures(
                                        onDragStart = { offset -> touchedTime = timeAtX(offset.x, plotSize.width.toFloat(), panOffset, visibleWindow) },
                                        onDrag = { change, _ -> touchedTime = timeAtX(change.position.x, plotSize.width.toFloat(), panOffset, visibleWindow) }
                                    )
                                }
                        ) {
                            val visible = remember(dataPoints, panOffset, visibleWindow) {
                                dataPoints.filter { it.time in panOffset..(panOffset + visibleWindow) }
                            }
                            if (visible.size >= 2) {
                                val series = buildList {
                                    if (hasTarget) {
                                        add(CurveSeries(ChartColorTargetPressure, ChartAxis.LEFT, dashed = true) { visible[it].targetPressure })
                                        add(CurveSeries(ChartColorTargetFlow, ChartAxis.LEFT, dashed = true) { visible[it].targetFlow })
                                        if (hasTemp) add(CurveSeries(ChartColorTargetTemperature, ChartAxis.RIGHT, dashed = true) { visible[it].targetTemperature })
                                    }
                                    add(CurveSeries(PRESSURE_COLOR, ChartAxis.LEFT) { visible[it].pressure })
                                    add(CurveSeries(FLOW_COLOR, ChartAxis.LEFT) { visible[it].flow })
                                    if (hasWRate) add(CurveSeries(WRATE_COLOR, ChartAxis.LEFT) { visible[it].weight })
                                    if (hasTemp) add(CurveSeries(TEMP_COLOR, ChartAxis.RIGHT) { visible[it].temperature })
                                    if (hasWeight) add(CurveSeries(WEIGHT_COLOR, ChartAxis.RIGHT) { if (visible[it].shotWeight > 0f) visible[it].shotWeight else visible[it].weight })
                                }
                                CurveChart(
                                    modifier = Modifier.fillMaxSize(),
                                    pointCount = visible.size,
                                    timeAt = { visible[it].time },
                                    series = series,
                                    leftMax = LEFT_MAX,
                                    rightMax = RIGHT_MAX,
                                    t0 = panOffset,
                                    t1 = panOffset + visibleWindow,
                                    crosshairTime = touchedTime
                                )
                            }

                            val closest = dataPoints.minByOrNull { abs((it.time - touchedTime).toFloat()) }
                            if (closest != null) {
                                Card(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp).padding(end = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Readout("t", String.format("%.1fs", closest.time))
                                        Readout("P", String.format("%.1f bar", closest.pressure), PRESSURE_COLOR)
                                        Readout("PF", String.format("%.1f", closest.flow), FLOW_COLOR)
                                        if (hasTemp) Readout("T", String.format("%.1f°C", closest.temperature), TEMP_COLOR)
                                        if (hasWeight) Readout("W", String.format("%.1fg", if (closest.shotWeight > 0f) closest.shotWeight else closest.weight), WEIGHT_COLOR)
                                        if (hasWRate) Readout("WR", String.format("%.1fg/s", closest.weight), WRATE_COLOR)
                                    }
                                }
                            }
                        }

                        // X-axis time labels (visible window)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val t0 = panOffset
                            val t1 = panOffset + visibleWindow
                            for (i in 0..4) {
                                val t = t0 + (t1 - t0) * i / 4f
                                Text(String.format("%.1fs", t), fontSize = 9.sp, color = AXIS_TEXT)
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier.width(30.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(5) { i -> Text("${RIGHT_MAX * (4 - i) / 4}", fontSize = 9.sp, color = AXIS_TEXT) }
                }
            }

            // Zoom + pan controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(20f) }) {
                    Icon(Icons.Default.ZoomIn, contentDescription = stringResource(R.string.history_chart_zoom_in))
                }
                IconButton(onClick = {
                    zoom = (zoom / 1.5f).coerceAtLeast(1f)
                    if (zoom <= 1f) panOffset = 0f
                }) {
                    Icon(Icons.Default.ZoomOut, contentDescription = stringResource(R.string.history_chart_zoom_out))
                }
                Text(String.format("%.0f×", zoom), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (zoom > 1f && maxPan > 0f) {
                    Slider(
                        value = panOffset,
                        onValueChange = { panOffset = it.coerceIn(0f, maxPan) },
                        valueRange = 0f..maxPan,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(stringResource(R.string.history_chart_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun produceShot(shotId: String): State<ShotEntity?> {
    return produceState<ShotEntity?>(null, shotId) {
        value = AppContainer.localRepo.getShotById(shotId)
    }
}

private fun timeAtX(x: Float, plotW: Float, t0: Float, visibleWindow: Float): Float {
    if (plotW <= 0f) return t0
    return (t0 + (x / plotW) * visibleWindow).coerceAtLeast(0f)
}

@Composable
private fun LegendDot(text: String, color: Color, isDashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            if (isDashed) {
                var x = 0f
                while (x < size.width) {
                    val end = (x + size.width * 0.4f).coerceAtMost(size.width)
                    drawLine(color, Offset(x, size.height / 2f), Offset(end, size.height / 2f), 2f)
                    x = end + size.width * 0.3f
                }
            } else drawCircle(color = color)
        }
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

package com.gagmate.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.data.local.entity.ShotEntity
import com.gagmate.app.data.model.ShotDataPoint
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotChartFullScreen(
    shot: ShotEntity,
    onClose: () -> Unit
) {
    val shotRecord = remember { shot.toShotRecord() }
    val dataPoints = remember { shotRecord.data }
    var touchedTime by remember { mutableFloatStateOf(-1f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shot.profileName.ifEmpty { "Shot Detail" }) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("Duration", String.format("%.1fs", shotRecord.duration))
                StatChip("Yield", String.format("%.1fg", shotRecord.volume))
            }

            Spacer(Modifier.height(8.dp))

            // Touch-interactive chart
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TouchChart(
                    dataPoints = dataPoints,
                    touchedTime = touchedTime,
                    onTouch = { t -> touchedTime = t },
                    modifier = Modifier.fillMaxSize()
                )

                // Data tooltip at touched point
                if (touchedTime >= 0f) {
                    val closest = dataPoints.minByOrNull { abs(it.time - touchedTime) }
                    if (closest != null) {
                        DataTooltip(
                            point = closest,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TouchChart(
    dataPoints: List<ShotDataPoint>,
    touchedTime: Float,
    onTouch: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.pointerInput(dataPoints) {
            detectTapGestures { offset ->
                val totalTime = dataPoints.lastOrNull()?.time?.coerceAtLeast(1f) ?: 60f
                val timeAtTap = (offset.x / size.width) * totalTime
                onTouch(timeAtTap.coerceIn(0f, totalTime))
            }
        }
    ) {
        if (dataPoints.size < 2) return@Canvas

        val plotWidth = size.width
        val plotHeight = size.height
        val maxPressure = 12f
        val maxFlow = 5f
        val totalTime = dataPoints.lastOrNull()?.time?.coerceAtLeast(60f) ?: 60f

        fun xPos(t: Float) = (t / totalTime * plotWidth).toFloat()
        fun yP(v: Float, mx: Float) = plotHeight - ((v / mx).coerceIn(0f, 1f) * plotHeight).toFloat()

        // Grid
        val gridColor = Color(0xFFD7CCC8).copy(alpha = 0.3f)
        for (i in 0..4) {
            val y = plotHeight * i / 4f
            drawLine(gridColor, Offset(0f, y), Offset(plotWidth, y), 1f)
        }

        // Pressure (blue)
        val pPath = Path()
        dataPoints.forEachIndexed { i, pt ->
            val x = xPos(pt.time); val y = yP(pt.pressure, maxPressure)
            if (i == 0) pPath.moveTo(x, y) else pPath.lineTo(x, y)
        }
        drawPath(pPath, Color(0xFF2196F3), style = Stroke(3f, cap = StrokeCap.Round))

        // Flow (yellow)
        val fPath = Path()
        dataPoints.forEachIndexed { i, pt ->
            val x = xPos(pt.time); val y = yP(pt.flow, maxFlow)
            if (i == 0) fPath.moveTo(x, y) else fPath.lineTo(x, y)
        }
        drawPath(fPath, Color(0xFFFFC107), style = Stroke(3f, cap = StrokeCap.Round))

        // Temperature (red)
        if (dataPoints.any { it.temperature > 0.5f }) {
            val tPath = Path()
            dataPoints.forEachIndexed { i, pt ->
                val x = xPos(pt.time); val y = yP(pt.temperature, 100f)
                if (i == 0) tPath.moveTo(x, y) else tPath.lineTo(x, y)
            }
            drawPath(tPath, Color(0xFFF44336), style = Stroke(2f, cap = StrokeCap.Round))
        }

        // Crosshair at touched point
        if (touchedTime >= 0f) {
            val cx = xPos(touchedTime.coerceIn(0f, totalTime))
            drawLine(Color(0xFF666666).copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, plotHeight), 2f)
        }
    }
}

@Composable
private fun DataTooltip(point: ShotDataPoint, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("t = ${String.format("%.1f", point.time)}s",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendDot("P ${String.format("%.1f", point.pressure)} bar", Color(0xFF2196F3))
                LegendDot("PF ${String.format("%.1f", point.flow)} ml/s", Color(0xFFFFC107))
            }
            if (point.temperature > 0f) {
                LegendDot("T ${String.format("%.1f", point.temperature)}°C", Color(0xFFF44336))
            }
            if (point.shotWeight > 0f) {
                LegendDot("W ${String.format("%.1f", point.shotWeight)}g", Color(0xFF8D6E63))
            }
        }
    }
}

@Composable
private fun LegendDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = color) }
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

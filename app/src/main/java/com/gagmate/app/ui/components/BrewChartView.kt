package com.gagmate.app.ui.components
import com.gagmate.app.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChartPoint(
    val time: Float,
    val pressure: Float,
    val flowRate: Float,
    val weight: Float = 0f,
    val weightChangeRate: Float = 0f,
    val temperature: Float = 0f,
    val targetPressure: Float = 0f,
    val targetFlowRate: Float = 0f,
    val targetTemperature: Float = 0f
)

// Colors matching user's spec
private val weightColor = Color(0xFF8D6E63)        // 褐色 (brown)
private val pressureColor = Color(0xFF2196F3)       // 蓝色 (blue)
private val flowColor = Color(0xFFFFC107)           // 黄色 (yellow)
private val weightRateColor = Color(0xFF4CAF50)     // 绿色 (green)
private val temperatureColor = Color(0xFFF44336)    // 红色 (red)

// Target colors (semi-transparent for dashed lines)
private val targetPressureColor = pressureColor.copy(alpha = 0.4f)
private val targetFlowColor = flowColor.copy(alpha = 0.4f)
private val targetTempColor = temperatureColor.copy(alpha = 0.4f)

private val gridColor = Color(0xFFD7CCC8).copy(alpha = 0.4f)
private val labelColor = Color(0xFF8D6E63)

private const val LEFT_AXIS_MAX = 16f
private const val RIGHT_AXIS_MAX = 100f

@Composable
fun BrewChartView(
    dataPoints: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    timeWindow: Float = 60f,
    progressTime: Float? = null
) {
    val displayPoints = if (progressTime != null) {
        dataPoints.filter { it.time <= progressTime }
    } else {
        dataPoints
    }
    val effectiveTimeWindow = progressTime?.let { it.coerceAtLeast(timeWindow) } ?: timeWindow

    val hasTargetData = dataPoints.any { it.targetPressure > 0f || it.targetFlowRate > 0f || it.targetTemperature > 0f }

    Column(modifier = modifier) {
        // Legend row — only show metrics with data present
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegendItem(color = pressureColor, label = stringResource(R.string.chart_pressure))
            LegendItem(color = flowColor, label = stringResource(R.string.chart_pump_flow))
            if (dataPoints.any { it.weightChangeRate > 0.1f || it.weight > 0f }) {
                LegendItem(color = weightRateColor, label = stringResource(R.string.chart_weight_rate))
            }
            if (dataPoints.any { it.temperature > 0.5f }) {
                LegendItem(color = temperatureColor, label = stringResource(R.string.chart_temperature))
            }
            if (dataPoints.any { it.weight > 0.1f }) {
                LegendItem(color = weightColor, label = stringResource(R.string.chart_weight))
            }
            if (hasTargetData) {
                LegendItem(color = targetPressureColor, label = "Target", isDashed = true)
            }
        }
        Spacer(Modifier.height(2.dp))

        Box(modifier = Modifier.fillMaxWidth().height(height)) {
            // Left Y axis (0-16: pressure, pump flow, weight change rate)
            Column(
                modifier = Modifier.fillMaxHeight().width(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("16", fontSize = 9.sp, color = labelColor)
                Text("12", fontSize = 9.sp, color = labelColor)
                Text("8", fontSize = 9.sp, color = labelColor)
                Text("4", fontSize = 9.sp, color = labelColor)
                Text("0", fontSize = 9.sp, color = labelColor)
            }

            // Right Y axis (0-100: temperature, weight)
            Column(
                modifier = Modifier.fillMaxHeight().align(Alignment.TopEnd).width(22.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("100", fontSize = 9.sp, color = labelColor)
                Text("75", fontSize = 9.sp, color = labelColor)
                Text("50", fontSize = 9.sp, color = labelColor)
                Text("25", fontSize = 9.sp, color = labelColor)
                Text("0", fontSize = 9.sp, color = labelColor)
            }

            Canvas(
                modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 22.dp, bottom = 16.dp)
            ) {
                val chartWidth = size.width
                val chartHeight = size.height
                val plotWidth = chartWidth
                val plotHeight = chartHeight

                // Grid lines (horizontal, 5 lines)
                for (i in 0..4) {
                    val y = plotHeight * i / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(plotWidth, y), 1f)
                }

                if (displayPoints.size < 2) return@Canvas

                val totalRange = dataPoints.lastOrNull()?.time?.coerceAtLeast(effectiveTimeWindow) ?: effectiveTimeWindow
                val minT = 0f
                val maxT = totalRange.coerceAtLeast(1f)
                val tRange = maxT - minT

                fun xPos(time: Float): Float = ((time - minT) / tRange * plotWidth).toFloat()
                fun yValLeft(value: Float): Float =
                    plotHeight - ((value / LEFT_AXIS_MAX).coerceIn(0f, 1f) * plotHeight).toFloat()
                fun yValRight(value: Float): Float =
                    plotHeight - ((value / RIGHT_AXIS_MAX).coerceIn(0f, 1f) * plotHeight).toFloat()

                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)

                // ── Target dashed lines (drawn first, behind actuals) ──

                if (hasTargetData) {
                    // Target pressure (blue dashed)
                    val targetPressurePath = Path()
                    displayPoints.forEachIndexed { idx, pt ->
                        if (pt.targetPressure > 0f) {
                            val x = xPos(pt.time)
                            val y = yValLeft(pt.targetPressure)
                            if (idx > 0 && !targetPressurePath.isEmpty) {
                                targetPressurePath.lineTo(x, y)
                            } else {
                                targetPressurePath.moveTo(x, y)
                            }
                        }
                    }
                    if (!targetPressurePath.isEmpty) {
                        drawPath(targetPressurePath, targetPressureColor,
                            style = Stroke(2f, pathEffect = dashEffect, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }

                    // Target pump flow (yellow dashed)
                    val targetFlowPath = Path()
                    displayPoints.forEachIndexed { idx, pt ->
                        if (pt.targetFlowRate > 0f) {
                            val x = xPos(pt.time)
                            val y = yValLeft(pt.targetFlowRate)
                            if (idx > 0 && !targetFlowPath.isEmpty) {
                                targetFlowPath.lineTo(x, y)
                            } else {
                                targetFlowPath.moveTo(x, y)
                            }
                        }
                    }
                    if (!targetFlowPath.isEmpty) {
                        drawPath(targetFlowPath, targetFlowColor,
                            style = Stroke(2f, pathEffect = dashEffect, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }

                    // Target temperature (red dashed)
                    val targetTempPath = Path()
                    displayPoints.forEachIndexed { idx, pt ->
                        if (pt.targetTemperature > 0f) {
                            val x = xPos(pt.time)
                            val y = yValRight(pt.targetTemperature)
                            if (idx > 0 && !targetTempPath.isEmpty) {
                                targetTempPath.lineTo(x, y)
                            } else {
                                targetTempPath.moveTo(x, y)
                            }
                        }
                    }
                    if (!targetTempPath.isEmpty) {
                        drawPath(targetTempPath, targetTempColor,
                            style = Stroke(2f, pathEffect = dashEffect, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }

                // ── Actual solid lines ──

                // Pressure (blue, left axis)
                val pressurePath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yValLeft(pt.pressure)
                    if (idx == 0) pressurePath.moveTo(x, y) else pressurePath.lineTo(x, y)
                }
                drawPath(pressurePath, pressureColor,
                    style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Pump flow (yellow, left axis)
                val flowPath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yValLeft(pt.flowRate)
                    if (idx == 0) flowPath.moveTo(x, y) else flowPath.lineTo(x, y)
                }
                drawPath(flowPath, flowColor,
                    style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Weight change rate (green, left axis)
                val wcrPath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yValLeft(pt.weightChangeRate)
                    if (idx == 0) wcrPath.moveTo(x, y) else wcrPath.lineTo(x, y)
                }
                drawPath(wcrPath, weightRateColor,
                    style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Temperature (red, right axis)
                val tempPath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yValRight(pt.temperature)
                    if (idx == 0) tempPath.moveTo(x, y) else tempPath.lineTo(x, y)
                }
                drawPath(tempPath, temperatureColor,
                    style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Weight (brown, right axis)
                val weightPath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yValRight(pt.weight)
                    if (idx == 0) weightPath.moveTo(x, y) else weightPath.lineTo(x, y)
                }
                drawPath(weightPath, weightColor,
                    style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // ── Current point markers ──
                if (displayPoints.isNotEmpty()) {
                    val last = displayPoints.last()
                    val lx = xPos(last.time)

                    // Small markers at the end of each line
                    val markerSize = 4f
                    drawCircle(pressureColor, markerSize, Offset(lx, yValLeft(last.pressure)))
                    drawCircle(flowColor, markerSize, Offset(lx, yValLeft(last.flowRate)))
                    drawCircle(weightRateColor, markerSize, Offset(lx, yValLeft(last.weightChangeRate)))
                    drawCircle(temperatureColor, markerSize, Offset(lx, yValRight(last.temperature)))
                    if (last.weight > 0f) {
                        drawCircle(weightColor, markerSize, Offset(lx, yValRight(last.weight)))
                    }

                    // Progress marker line
                    drawLine(
                        Color(0xFF8D6E63).copy(alpha = 0.25f),
                        Offset(lx, 0f),
                        Offset(lx, plotHeight),
                        strokeWidth = 1.5f
                    )
                }
            }

            // X-axis labels
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(start = 24.dp, end = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    Text(
                        "${(effectiveTimeWindow * i / 4).toInt()}s",
                        fontSize = 9.sp,
                        color = labelColor
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.LegendItem(color: Color, label: String, isDashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(10.dp)) {
            if (isDashed) {
                val dashLen = size.width * 0.4f
                val gap = size.width * 0.3f
                var x = 0f
                while (x < size.width) {
                    val end = (x + dashLen).coerceAtMost(size.width)
                    drawLine(color, Offset(x, size.height / 2f), Offset(end, size.height / 2f), 2f)
                    x = end + gap
                }
            } else {
                drawCircle(color = color)
            }
        }
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
    }
}

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
    val targetTemperature: Float = 0f,
    /** Accumulated weight in grams (from shotWeight column). */
    val shotWeight: Float = 0f
)

// Colors matching user's spec
private val weightColor = Color(0xFF8D6E63)        // 褐色 (brown)
private val pressureColor = Color(0xFF2196F3)       // 蓝色 (blue)
private val flowColor = Color(0xFFFFC107)           // 黄色 (yellow)
private val weightRateColor = Color(0xFF4CAF50)     // 绿色 (green)
private val temperatureColor = Color(0xFFF44336)    // 红色 (red)

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
    val effectiveTimeWindow = progressTime?.let { it.coerceAtLeast(timeWindow) }
        ?: (displayPoints.lastOrNull()?.time?.coerceAtLeast(timeWindow) ?: timeWindow)

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
            if (dataPoints.any { (if (it.shotWeight > 0f) it.shotWeight else it.weight) > 0.1f }) {
                LegendItem(color = weightColor, label = stringResource(R.string.chart_weight))
            }
            if (hasTargetData) {
                LegendItem(color = ChartColorTargetPressure, label = "Target", isDashed = true)
            }
        }
        Spacer(Modifier.height(2.dp))

        Box(modifier = if (height == Dp.Unspecified) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(height)) {
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

            val plotModifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 22.dp, bottom = 16.dp)

            if (displayPoints.size >= 2) {
                val pts = displayPoints
                val series = buildList {
                    if (hasTargetData) {
                        add(CurveSeries(ChartColorTargetPressure, ChartAxis.LEFT, dashed = true) { pts[it].targetPressure })
                        add(CurveSeries(ChartColorTargetFlow, ChartAxis.LEFT, dashed = true) { pts[it].targetFlowRate })
                        add(CurveSeries(ChartColorTargetTemperature, ChartAxis.RIGHT, dashed = true) { pts[it].targetTemperature })
                    }
                    add(CurveSeries(pressureColor, ChartAxis.LEFT) { pts[it].pressure })
                    add(CurveSeries(flowColor, ChartAxis.LEFT) { pts[it].flowRate })
                    if (pts.any { it.weightChangeRate > 0.1f }) {
                        add(CurveSeries(weightRateColor, ChartAxis.LEFT) { pts[it].weightChangeRate })
                    }
                    if (pts.any { it.temperature > 0.5f }) {
                        add(CurveSeries(temperatureColor, ChartAxis.RIGHT) { pts[it].temperature })
                    }
                    if (pts.any { (if (it.shotWeight > 0f) it.shotWeight else it.weight) > 0.1f }) {
                        add(CurveSeries(weightColor, ChartAxis.RIGHT) { if (pts[it].shotWeight > 0f) pts[it].shotWeight else pts[it].weight })
                    }
                }
                CurveChart(
                    modifier = plotModifier,
                    pointCount = pts.size,
                    timeAt = { pts[it].time },
                    series = series,
                    leftMax = LEFT_AXIS_MAX,
                    rightMax = RIGHT_AXIS_MAX,
                    t0 = 0f,
                    t1 = effectiveTimeWindow
                )
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

package com.gagmate.app.ui.components
import com.gagmate.app.R

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ChartPoint(
    val time: Float,
    val pressure: Float,
    val flowRate: Float
)

@Composable
fun BrewChartView(
    dataPoints: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    maxPressure: Float = 12f,
    maxFlow: Float = 5f,
    timeWindow: Float = 60f,
    progressTime: Float? = null
) {
    val displayPoints = if (progressTime != null) {
        dataPoints.filter { it.time <= progressTime }
    } else {
        dataPoints
    }
    val effectiveTimeWindow = progressTime?.let { it.coerceAtLeast(timeWindow) } ?: timeWindow

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(color = Color(0xFFBF8F6B))
                }
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dashboard_pressure) + " (bar)", style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(color = Color(0xFF4CAF50))
                }
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.dashboard_flow_rate) + " (ml/s)", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxWidth().height(height)) {
            Column(
                modifier = Modifier.fillMaxHeight().width(32.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("12", fontSize = 10.sp, color = Color(0xFF8D6E63))
                Text("9", fontSize = 10.sp, color = Color(0xFF8D6E63))
                Text("6", fontSize = 10.sp, color = Color(0xFF8D6E63))
                Text("3", fontSize = 10.sp, color = Color(0xFF8D6E63))
                Text("0", fontSize = 10.sp, color = Color(0xFF8D6E63))
            }

            Canvas(
                modifier = Modifier.fillMaxSize().padding(start = 32.dp, bottom = 16.dp)
            ) {
                val chartWidth = size.width
                val chartHeight = size.height
                val plotWidth = chartWidth
                val plotHeight = chartHeight

                // Grid
                val gridColor = Color(0xFFD7CCC8).copy(alpha = 0.5f)
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
                fun yVal(value: Float, maxV: Float): Float = plotHeight - ((value / maxV).coerceIn(0f, 1f) * plotHeight).toFloat()

                // Pressure line
                val pressurePath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yVal(pt.pressure, maxPressure)
                    if (idx == 0) pressurePath.moveTo(x, y) else pressurePath.lineTo(x, y)
                }
                drawPath(pressurePath, Color(0xFFBF8F6B), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Flow line
                val flowPath = Path()
                displayPoints.forEachIndexed { idx, pt ->
                    val x = xPos(pt.time)
                    val y = yVal(pt.flowRate, maxFlow)
                    if (idx == 0) flowPath.moveTo(x, y) else flowPath.lineTo(x, y)
                }
                drawPath(flowPath, Color(0xFF4CAF50), style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Current point markers
                if (displayPoints.isNotEmpty()) {
                    val last = displayPoints.last()
                    val lx = xPos(last.time)
                    drawCircle(Color(0xFFBF8F6B), 6f, Offset(lx, yVal(last.pressure, maxPressure)))
                    drawCircle(Color(0xFF4CAF50), 6f, Offset(lx, yVal(last.flowRate, maxFlow)))

                    // Progress marker line
                    val markerX = xPos(last.time)
                    drawLine(
                        Color(0xFF8D6E63).copy(alpha = 0.3f),
                        Offset(markerX, 0f),
                        Offset(markerX, plotHeight),
                        strokeWidth = 1.5f
                    )
                }
            }

            // X-axis labels
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(start = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (i in 0..4) {
                    Text(
                        "${(effectiveTimeWindow * i / 4).toInt()}s",
                        fontSize = 10.sp,
                        color = Color(0xFF8D6E63),
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
        }
    }
}

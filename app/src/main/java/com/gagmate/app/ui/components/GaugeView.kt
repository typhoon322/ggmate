package com.gagmate.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.theme.GagMateSpacing
import com.gagmate.app.theme.gagMateColors

/**
 * Arc gauge for machine metrics (temperature, pressure, flow, steam).
 *
 * - Track + fill colors come from the [gagMateColors] token set.
 * - Animated sweep gives a sense of live change without being distracting.
 * - A single merged content description ("Boiler T: 93.0 °C") is provided so
 *   screen readers announce the value once instead of reading the raw pieces.
 */
@Composable
fun GaugeView(
    value: Float,
    maxValue: Float,
    label: String,
    unit: String,
    gaugeColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
    strokeWidth: Dp = 12.dp,
    /** When true (e.g. machine not connected, no data) show "—" instead of 0. */
    showDash: Boolean = false
) {
    val colors = gagMateColors()
    val resolvedGaugeColor = if (gaugeColor == Color.Unspecified) colors.gaugeTemperature else gaugeColor

    val animatedValue by animateFloatAsState(
        targetValue = if (showDash) 0f else value / maxValue,
        animationSpec = tween(durationMillis = 800),
        label = "gauge"
    )

    val valueText = if (showDash) "—" else "%.1f".format(value)
    val semanticsText = if (showDash) "$label: —" else "$label: ${"%.1f".format(value)} $unit"

    Column(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = semanticsText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                val padding = strokeWidth.toPx() / 2
                val arcSize = size.toPx() - strokeWidth.toPx()
                val topLeft = Offset(padding, padding)

                // Background track (270deg starting at 135deg)
                drawArc(
                    color = colors.gaugeTrack,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = stroke
                )

                // Foreground arc
                drawArc(
                    color = resolvedGaugeColor,
                    startAngle = 135f,
                    sweepAngle = 270f * animatedValue.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = stroke
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value / 5).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(GagMateSpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

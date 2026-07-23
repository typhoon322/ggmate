package com.gagmate.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.gagmate.app.R
import com.gagmate.app.theme.GagMateSpacing
import com.gagmate.app.theme.GagMateShape
import com.gagmate.app.theme.gagMateColors

/**
 * Connection status indicator with an animated pulsing dot.
 * The dot color comes from the semantic [gagMateColors] token set, so it
 * stays consistent and accessible in both light and dark themes.
 */
@Composable
fun StatusIndicator(
    isConnected: Boolean,
    label: String = if (isConnected) "Connected" else "Disconnected",
    modifier: Modifier = Modifier
) {
    val colors = gagMateColors()
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val dotColor by animateColorAsState(
        targetValue = if (isConnected) colors.success else colors.disabled,
        label = "dotColor",
        animationSpec = tween(300)
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GagMateSpacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = if (isConnected) pulseAlpha else 1f))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Machine operation-state badge (Idle / Brewing / Heating / Steam).
 * Colors are driven by semantic tokens and the label is localised.
 * Exposes an accessible content description so screen readers announce the
 * machine state instead of just reading the raw text.
 */
@Composable
fun MachineStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val colors = gagMateColors()

    val (bg, labelRes) = when (status) {
        "brew" -> colors.stateBrewing to R.string.dashboard_status_brewing
        "preinfusion", "heating" -> colors.stateHeating to R.string.dashboard_status_heating
        "steam" -> colors.stateSteam to R.string.dashboard_status_steam
        "offline" -> colors.stateOffline to R.string.dashboard_status_offline
        "connecting" -> colors.info to R.string.dashboard_status_connecting
        "reconnecting" -> colors.info to R.string.dashboard_status_reconnecting
        else -> colors.stateIdle to R.string.dashboard_status_idle
    }
    val label = stringResource(labelRes)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(GagMateShape.pill))
            .background(bg)
            .padding(horizontal = GagMateSpacing.md, vertical = GagMateSpacing.xs)
            .clearAndSetSemantics {
                contentDescription = "Machine status: $label"
            }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onState
        )
    }
}

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Connection status indicator with animated pulsing dot.
 */
@Composable
fun StatusIndicator(
    isConnected: Boolean,
    label: String = if (isConnected) "Connected" else "Disconnected",
    modifier: Modifier = Modifier
) {
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
        targetValue = if (isConnected) Color(0xFF43A047) else Color(0xFFBDBDBD),
        label = "dotColor",
        animationSpec = tween(300)
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
 * Machine status badge showing current operation state.
 */
@Composable
fun MachineStatusBadge(
    status: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        status == "brew" -> Color(0xFFE65100)
        status == "preinfusion" -> Color(0xFFFF8F00)
        status == "steam" -> Color(0xFF78909C)
        isActive -> Color(0xFFFF6F00)
        else -> Color(0xFF43A047)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            color = Color.White
        )
    }
}

package com.gagmate.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * GagMate design tokens — the single source of truth for spacing, shape,
 * elevation, and semantic colors across the whole app.
 *
 * Design principles
 * ------------------------------------------------------------------
 * - Spacing uses a 4dp base unit (4 / 8 / 12 / 16 / 24 / 32) so rhythm stays
 *   consistent everywhere.
 * - Shape uses a restrained 3-step radius scale (sm / md / lg) plus a pill.
 * - Semantic colors (success / warning / info / machine states / gauges) live
 *   here, outside the M3 core scheme, and are chosen to meet WCAG AA contrast
 *   (>= 4.5:1 for normal text on fill, >= 3:1 for large text / icons).
 */

// ── Spacing scale (4dp base) ─────────────────────────────────
object GagMateSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

// ── Shape scale ──────────────────────────────────────────────
object GagMateShape {
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val pill: Dp = 999.dp
}

// ── Tonal elevation scale (dp) ───────────────────────────────
object GagMateElevation {
    val none: Dp = 0.dp
    val sm: Dp = 1.dp
    val md: Dp = 3.dp
    val lg: Dp = 6.dp
}

/**
 * Semantic / extended color roles for GagMate that go beyond the M3 core scheme.
 * Kept immutable so it is cheap to read inside composables.
 */
@Immutable
data class GagMateExtendedColors(
    // Feedback semantics
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
    val onInfo: Color,

    // Machine operation states (badge backgrounds + on-color)
    val stateBrewing: Color,
    val stateHeating: Color,
    val stateIdle: Color,
    val stateSteam: Color,
    val stateOffline: Color,
    val onState: Color,

    // Gauge fills
    val gaugeTrack: Color,
    val gaugeTemperature: Color,
    val gaugePressure: Color,
    val gaugeFlow: Color,
    val gaugeSteam: Color,

    // Supporting
    val divider: Color,
    val disabled: Color
)

val LightExtendedColors = GagMateExtendedColors(
    success = Color(0xFF2E7D32),
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFFB45309),
    onWarning = Color(0xFFFFFFFF),
    info = Color(0xFF1565C0),
    onInfo = Color(0xFFFFFFFF),

    stateBrewing = Color(0xFFC2410C),
    stateHeating = Color(0xFFB45309),
    stateIdle = Color(0xFF2E7D32),
    stateSteam = Color(0xFF455A64),
    stateOffline = Color(0xFFB71C1C),
    onState = Color(0xFFFFFFFF),

    gaugeTrack = Color(0xFFE0D5D0),
    gaugeTemperature = Color(0xFF6D4C41),
    gaugePressure = Color(0xFFBF8F6B),
    gaugeFlow = Color(0xFF8D6E63),
    gaugeSteam = Color(0xFF78909C),

    divider = Color(0xFFC4B9B3),
    disabled = Color(0xFFBDBDBD)
)

val DarkExtendedColors = GagMateExtendedColors(
    success = Color(0xFF81C784),
    onSuccess = Color(0xFF0B2013),
    warning = Color(0xFFF0B36B),
    onWarning = Color(0xFF2A1A05),
    info = Color(0xFF90CAF9),
    onInfo = Color(0xFF06223F),

    stateBrewing = Color(0xFFFF8A65),
    stateHeating = Color(0xFFFFB74D),
    stateIdle = Color(0xFF81C784),
    stateSteam = Color(0xFF90A4AE),
    stateOffline = Color(0xFFEF9A9A),
    onState = Color(0xFF1B1B1F),

    gaugeTrack = Color(0xFF4E3B35),
    gaugeTemperature = Color(0xFFD7CCC8),
    gaugePressure = Color(0xFFBF8F6B),
    gaugeFlow = Color(0xFFBCAAA4),
    gaugeSteam = Color(0xFF90A4AE),

    divider = Color(0xFF5D4F49),
    disabled = Color(0xFF6E6E6E)
)

val LocalGagMateColors = compositionLocalOf { LightExtendedColors }

/** Ergonomic accessor for the current extended color set. */
@Composable
fun gagMateColors(): GagMateExtendedColors = LocalGagMateColors.current

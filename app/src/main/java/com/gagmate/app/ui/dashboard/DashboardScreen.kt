package com.gagmate.app.ui.dashboard

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gagmate.app.BuildConfig
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.session.ConnectionState
import com.gagmate.app.theme.GagMateShape
import com.gagmate.app.theme.GagMateSpacing
import com.gagmate.app.theme.gagMateColors
import com.gagmate.app.ui.components.BrewChartView
import com.gagmate.app.ui.components.GaugeView
import com.gagmate.app.ui.components.MachineStatusBadge
import com.gagmate.app.ui.components.generateProfileChartPoints
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    // ── Direct session reads via LaunchedEffect + mutableStateOf ──
    val session = AppContainer.machineSession
    var directSensorT by remember { mutableStateOf(0f) }
    var directSensorP by remember { mutableStateOf(0f) }
    var directBrewActive by remember { mutableStateOf(false) }
    var directProfileName by remember { mutableStateOf("") }
    var directMode by remember { mutableStateOf(0) }
    var directConnState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var directUptime by remember { mutableStateOf(0) }

    SideEffect {
        if (BuildConfig.DEBUG) {
            Log.d("GagMateUI", "DISPLAY: T=${directSensorT} P=${directSensorP} mode=${directMode} prof=${directProfileName}")
        }
    }

    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: sensorSnapshot collect starting")
        try {
            session.sensorSnapshot.collect { s ->
                if (BuildConfig.DEBUG) Log.d("GagMateUI", "SENSOR: T=${s.temperature} P=${s.pressure} WL=${s.waterLevel}")
                directSensorT = s.temperature
                directSensorP = s.pressure
            }
        } catch (e: Exception) {
            Log.e("GagMateUI", "sensor collect CRASH", e)
        }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: brewActive collect")
        session.brewActive.collect { directBrewActive = it }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: profileName collect")
        session.selectedProfileName.collect {
            if (BuildConfig.DEBUG) Log.d("GagMateUI", "PROFILE: ${it}")
            directProfileName = it
        }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: machineMode collect")
        session.machineMode.collect {
            if (BuildConfig.DEBUG) Log.d("GagMateUI", "MODE: ${it}")
            directMode = it
        }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: connState collect")
        session.connectionState.collect {
            if (BuildConfig.DEBUG) Log.d("GagMateUI", "CONN: ${it}")
            directConnState = it
        }
    }
    // Profile setpoints from the active profile (not the live sensor target values).
    var directProfileSetpointTemp by remember { mutableStateOf(0f) }
    var directProfileTargetWeight by remember { mutableStateOf(0f) }
    var directProfileTargetPressure by remember { mutableStateOf(0f) }
    var directProfileTargetFlow by remember { mutableStateOf(0f) }
    var directProfileTargetTime by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: activeProfileSetpointTemp collect")
        session.activeProfileSetpointTemp.collect { directProfileSetpointTemp = it }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("GagMateUI", "LAUNCH: activeProfileTargetWeight collect")
        session.activeProfileTargetWeight.collect { directProfileTargetWeight = it }
    }
    LaunchedEffect(Unit) {
        session.activeProfileTargetPressure.collect { directProfileTargetPressure = it }
    }
    LaunchedEffect(Unit) {
        session.activeProfileTargetFlow.collect { directProfileTargetFlow = it }
    }
    LaunchedEffect(Unit) {
        session.activeProfileTargetTime.collect { directProfileTargetTime = it }
    }
    // Full phases of the selected profile, for the curve chart.
    var directProfilePhases by remember { mutableStateOf<List<com.gagmate.app.data.model.BrewPhase>>(emptyList()) }
    LaunchedEffect(Unit) {
        session.selectedProfilePhases.collect { directProfilePhases = it }
    }
    // Machine id of the selected profile — used to fetch its full detail (with
    // reliable phase targets) via REST. The WS phase stream is kept as a
    // fallback because some firmwares don't push it.
    var directSelectedProfileId by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        session.selectedProfileId.collect { directSelectedProfileId = it }
    }
    var restProfilePhases by remember { mutableStateOf<List<com.gagmate.app.data.model.BrewPhase>>(emptyList()) }
    LaunchedEffect(directSelectedProfileId, directProfileName) {
        restProfilePhases = emptyList()
        val id = directSelectedProfileId
        val name = directProfileName
        if (id > 0 || name.isNotBlank()) {
            try {
                restProfilePhases = AppContainer.machineRepo.fetchProfilePhases(
                    if (id > 0) id.toString() else null,
                    name
                )
            } catch (_: Exception) { }
            // Offline fallback: read cached phases from the local DB (populated
            // by the syncShots backfill), so the active-profile curve still
            // draws when the machine is not reachable.
            if (restProfilePhases.isEmpty() && name.isNotBlank()) {
                runCatching {
                    AppContainer.profileRepo.getAllProfiles()
                        .firstOrNull { it.name == name }
                        ?.toShotProfile()?.phases
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { restProfilePhases = it }
                }
            }
        }
    }
    // Prefer the REST-fetched phases (authoritative targets); fall back to the
    // WS stream if REST is unavailable.
    val effectivePhases = if (restProfilePhases.isNotEmpty()) restProfilePhases else directProfilePhases

    val isConnected = directConnState == ConnectionState.CONNECTED
    val flushActive = directMode == 2

    // Merged machine status for the hero badge: connection state folds into
    // the same 空闲 / 萃取中 indicator instead of a separate row.
    val heroStatus = when (directConnState) {
        ConnectionState.CONNECTED -> if (directBrewActive) "brew" else "idle"
        ConnectionState.CONNECTING -> "connecting"
        ConnectionState.RECONNECTING -> "reconnecting"
        else -> "offline"
    }

    // ViewModel-based (for commands only, not display)
    val chartData by viewModel.chartData.collectAsState()
    val liveWeight by viewModel.liveWeight.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(Unit) { viewModel.startPolling() }
    DisposableEffect(Unit) { onDispose { viewModel.stopPolling() } }

    // Auto-dismiss transient info messages
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // ── Pinned hero: machine status, fixed below the system status bar ──
            HeroStatusCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GagMateSpacing.lg, vertical = GagMateSpacing.sm),
                status = heroStatus,
                profileName = directProfileName,
                setpoint = directProfileSetpointTemp,
                uptimeSeconds = directUptime,
                isConnected = isConnected,
                onRefresh = { viewModel.refresh() },
                onOpenSettings = onOpenSettings
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize().weight(1f)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (maxWidth > 720.dp) {
                                Modifier.width(720.dp).align(Alignment.TopCenter)
                            } else {
                                Modifier
                            }
                        ),
                    contentPadding = PaddingValues(horizontal = GagMateSpacing.lg, vertical = GagMateSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(GagMateSpacing.lg)
                ) {
                    // ── Current machine readouts: temperature & pressure ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        GaugeView(
                            value = directSensorT,
                            maxValue = 110f,
                            label = stringResource(R.string.dashboard_boiler_t),
                            unit = "°C",
                            gaugeColor = gagMateColors().gaugeTemperature,
                            showDash = !isConnected
                        )
                        GaugeView(
                            value = directSensorP,
                            maxValue = 12f,
                            label = stringResource(R.string.dashboard_pressure),
                            unit = "bar",
                            gaugeColor = gagMateColors().gaugePressure,
                            showDash = !isConnected
                        )
                    }
                }

                // ── Selected profile curve chart ──
                item {
                    ProfileGlobalsCard(
                        profileName = directProfileName,
                        phases = effectivePhases,
                        setpointTemp = directProfileSetpointTemp,
                        targetWeight = directProfileTargetWeight,
                        targetPressure = directProfileTargetPressure,
                        targetFlow = directProfileTargetFlow,
                        targetTime = directProfileTargetTime
                    )
                }

                    // ── Machine controls ──
                    item {
                        ControlCard(
                            isConnected = isConnected,
                            brewActive = directBrewActive,
                            flushActive = flushActive,
                            setpoint = directProfileSetpointTemp,
                            targetWeight = directProfileTargetWeight,
                            onFlush = viewModel::flush,
                            onTare = viewModel::tare,
                            onSetSetpoint = viewModel::setSetpoint,
                            onSetWeight = viewModel::setWeight
                        )
                    }

                    // ── Live brew chart (only while data is streaming) ──
                    if (chartData.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(GagMateShape.lg)
                            ) {
                                Column(modifier = Modifier.padding(GagMateSpacing.lg)) {
                                    Text(
                                        text = stringResource(R.string.dashboard_live_chart),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(GagMateSpacing.sm))
                                    BrewChartView(
                                        dataPoints = chartData,
                                        modifier = Modifier.fillMaxWidth(),
                                        height = 200.dp
                                    )
                                }
                            }
                        }
                    }

                    // ── Active brew summary (live weight shown for volume) ──
                    if (directBrewActive) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(GagMateShape.lg),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(GagMateSpacing.lg),
                                    verticalArrangement = Arrangement.spacedBy(GagMateSpacing.md)
                                ) {
                                    Text(
                                        text = stringResource(R.string.dashboard_active_brew),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        MetricItem(stringResource(R.string.dashboard_time), "--")
                                        MetricItem(stringResource(R.string.dashboard_volume), "%.0f ml".format(liveWeight))
                                        MetricItem(stringResource(R.string.dashboard_pump), "--")
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(GagMateSpacing.sm)) }
                }

                // Transient info message (success/info from control commands)
                if (message != null) {
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(GagMateSpacing.lg)
                    ) {
                        Text(message ?: "")
                    }
                }
            }
        }
    }
}

// ── Hero status card ────────────────────────────────────────
@Composable
private fun HeroStatusCard(
    modifier: Modifier = Modifier,
    status: String,
    profileName: String,
    setpoint: Float,
    uptimeSeconds: Int,
    isConnected: Boolean,
    onRefresh: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GagMateShape.lg)
    ) {
        Column(
            modifier = Modifier.padding(GagMateSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GagMateSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(GagMateSpacing.xs)
                ) {
                    Text(
                        text = if (profileName.isNotBlank()) profileName else stringResource(R.string.dashboard_no_profile),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.dashboard_active_profile),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                MachineStatusBadge(
                    status = status
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dashboard_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            }

            Divider(color = gagMateColors().divider, thickness = 1.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MetricItem(
                    stringResource(R.string.dashboard_setpoint_short),
                    metricOrDash(isConnected, setpoint) { "%.0f°C".format(it) }
                )
                MetricItem(
                    stringResource(R.string.dashboard_hero_uptime),
                    if (isConnected && uptimeSeconds > 0) formatUptime(uptimeSeconds) else "—"
                )
            }
        }
    }
}

// ── Selected profile curve chart card ─────────────────────
@Composable
private fun ProfileGlobalsCard(
    profileName: String,
    phases: List<com.gagmate.app.data.model.BrewPhase>,
    setpointTemp: Float,
    targetWeight: Float,
    targetPressure: Float,
    targetFlow: Float,
    targetTime: Float
) {
    val chartPoints = remember(phases) { generateProfileChartPoints(phases) }
    val hasScalars = setpointTemp > 0f || targetWeight > 0f ||
            targetPressure > 0f || targetFlow > 0f || targetTime > 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GagMateShape.lg)
    ) {
        Column(
            modifier = Modifier.padding(GagMateSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GagMateSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dashboard_profile_curve),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                if (profileName.isNotBlank()) {
                    Text(
                        text = profileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when {
                // Preferred: draw the selected profile's curve.
                chartPoints.isNotEmpty() -> {
                    BrewChartView(
                        dataPoints = chartPoints,
                        modifier = Modifier.fillMaxWidth(),
                        height = 200.dp
                    )
                }
                // Fallback: no phases yet but we do have scalar setpoints.
                hasScalars -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricItem(stringResource(R.string.dashboard_setpoint_short), fmt(setpointTemp, "°C"))
                        MetricItem(stringResource(R.string.dashboard_weight), fmt(targetWeight, "g"))
                        MetricItem(stringResource(R.string.dashboard_target_time), fmt(targetTime, "s"))
                    }
                }
                // Nothing available.
                else -> {
                    Text(
                        text = stringResource(R.string.dashboard_no_profile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Machine controls card ───────────────────────────────────
@Composable
private fun ControlCard(
    isConnected: Boolean,
    brewActive: Boolean,
    flushActive: Boolean,
    setpoint: Float,
    targetWeight: Float,
    onFlush: () -> Unit,
    onTare: () -> Unit,
    onSetSetpoint: (Float) -> Unit,
    onSetWeight: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GagMateShape.lg)
    ) {
        Column(
            modifier = Modifier.padding(GagMateSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GagMateSpacing.md)
        ) {
            Text(
                text = stringResource(R.string.dashboard_machine_controls),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // Primary actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GagMateSpacing.md)
            ) {
                if (flushActive) {
                    Button(
                        onClick = onFlush,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = gagMateColors().warning
                        )
                    ) {
                        Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(GagMateSpacing.sm))
                        Text("Flushing", maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = onFlush,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                        enabled = isConnected && !brewActive
                    ) {
                        Icon(Icons.Default.WaterDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(GagMateSpacing.sm))
                        Text(stringResource(R.string.dashboard_flush), maxLines = 1)
                    }
                }

                OutlinedButton(
                    onClick = onTare,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    enabled = isConnected && !brewActive
                ) {
                    Text("TARE", fontSize = 14.sp)
                }
            }

            // Setpoint stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.dashboard_setpoint_short), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = metricOrDash(isConnected, setpoint) { "%.0f°C".format(it) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GagMateSpacing.sm)) {
                    StepperButton("-", enabled = isConnected) { onSetSetpoint((setpoint - 1f).coerceAtLeast(0f)) }
                    StepperButton("+", enabled = isConnected) { onSetSetpoint(setpoint + 1f) }
                }
            }

            // Weight stepper
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = stringResource(R.string.dashboard_weight), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = metricOrDash(isConnected, targetWeight) { "%.1fg".format(it) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GagMateSpacing.sm)) {
                    StepperButton("-", enabled = isConnected) { onSetWeight((targetWeight - 1f).coerceAtLeast(0f)) }
                    StepperButton("+", enabled = isConnected) { onSetWeight(targetWeight + 1f) }
                }
            }
        }
    }
}

// ── Square stepper button (>= 44dp touch target) ────────────
@Composable
private fun StepperButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text, fontSize = 18.sp)
    }
}

// ── Metric tile (value + label, accessible) ─────────────────
@Composable
private fun MetricItem(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────
private fun formatUptime(secs: Int): String {
    val h = secs / 3600
    val m = (secs % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** Format a profile global value, showing "—" when not set (0). */
private fun fmt(v: Float, unit: String): String =
    if (v > 0f) "%.1f%s".format(v, unit) else "—"

/**
 * Show a machine metric, or "—" when there is no data to display
 * (machine not connected, or the value is absent / 0).
 */
private fun metricOrDash(connected: Boolean, value: Float, format: (Float) -> String): String =
    if (connected && value > 0f) format(value) else "—"

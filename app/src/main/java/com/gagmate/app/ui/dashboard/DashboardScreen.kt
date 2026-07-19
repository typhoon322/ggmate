package com.gagmate.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Water
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.session.ConnectionState
import com.gagmate.app.theme.*
import com.gagmate.app.ui.components.GaugeView
import com.gagmate.app.ui.components.MachineStatusBadge
import com.gagmate.app.ui.components.StatusIndicator
import com.gagmate.app.ui.components.BrewChartView
import androidx.lifecycle.viewmodel.compose.viewModel

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
    var directSensorTT by remember { mutableStateOf(0f) }
    var directSensorWL by remember { mutableStateOf(0) }
    var directBrewActive by remember { mutableStateOf(false) }
    var directProfileName by remember { mutableStateOf("") }
    var directMode by remember { mutableStateOf(0) }
    var directConnState by remember { mutableStateOf(ConnectionState.DISCONNECTED) }
    var directUptime by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        session.sensorSnapshot.collect { s ->
            directSensorT = s.temperature
            directSensorP = s.pressure
            directSensorTT = s.targetTemperature
            directSensorWL = s.waterLevel
        }
    }
    LaunchedEffect(Unit) {
        session.brewActive.collect { directBrewActive = it }
    }
    LaunchedEffect(Unit) {
        session.selectedProfileName.collect { directProfileName = it }
    }
    LaunchedEffect(Unit) {
        session.machineMode.collect { directMode = it }
    }
    LaunchedEffect(Unit) {
        session.connectionState.collect { directConnState = it }
    }
    val isConnected = directConnState == ConnectionState.CONNECTED
    val isLoading = directConnState == ConnectionState.CONNECTING
    val flushActive = directMode == 2

    // ViewModel-based (for commands only, not display)
    val chartData by viewModel.chartData.collectAsState()
    val liveWeight by viewModel.liveWeight.collectAsState()
    val targetWeight by viewModel.targetWeight.collectAsState()
    val error by viewModel.error.collectAsState()

    var steamOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startPolling()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }

    // Sync steam status from machine state
    LaunchedEffect(false) {
        steamOn = false == true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            isConnected = isConnected,
                            label = if (isConnected) stringResource(R.string.dashboard_connected) else stringResource(R.string.dashboard_connecting)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dashboard_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (isConnected) {
                FloatingActionButton(
                    onClick = {
                        if (directBrewActive == true) viewModel.stopBrew()
                        else viewModel.startBrew()
                    },
                    containerColor = if (directBrewActive == true)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = if (directBrewActive == true)
                            Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (directBrewActive == true) "Stop" else "Start Brew",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null -> {
                    // No data yet - show connection error
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_cannot_connect),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onOpenSettings) {
                            Text(stringResource(R.string.dashboard_configure))
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Machine status bar
                        item {
                            // DEBUG: raw data diagnostic
                            Text(
                                text = "DBG: t=${"%.1f".format(directSensorT)}C P=${"%.2f".format(directSensorP)}bar W=${directSensorWL}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            // Machine status bar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MachineStatusBadge(
                                    status = if (directBrewActive == true) "brew" else "idle",
                                    isActive = directBrewActive == true
                                )
                                if (directProfileName?.isNotBlank() == true) {
                                    Text(
                                        text = directProfileName,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Machine Controls
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.dashboard_machine_controls),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Flush button
                                        if (flushActive) {
                                            Button(
                                                onClick = { viewModel.flush() },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.tertiary
                                                )
                                            ) {
                                                Icon(
                                                    Icons.Default.WaterDrop,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text("Flushing", maxLines = 1)
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = { viewModel.flush() },
                                                modifier = Modifier.weight(1f),
                                                enabled = isConnected && directBrewActive != true
                                            ) {
                                                Icon(
                                                    Icons.Default.WaterDrop,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(stringResource(R.string.dashboard_flush), maxLines = 1)
                                            }
                                        }

                                        // Tare button
                                        OutlinedButton(
                                            onClick = { viewModel.tare() },
                                            modifier = Modifier.weight(1f),
                                            enabled = isConnected && directBrewActive != true
                                        ) {
                                            Text("TARE", fontSize = 14.sp)
                                        }
                                    }

                                    // Temperature setpoint
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.dashboard_setpoint),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = String.format("%.0f°C", directSensorTT ?: 0f),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = {
                                                    val newVal = (directSensorTT ?: 93f) - 1f
                                                    viewModel.setSetpoint(newVal)
                                                },
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                enabled = isConnected
                                            ) {
                                                Text("-", fontSize = 18.sp)
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    val newVal = (directSensorTT ?: 93f) + 1f
                                                    viewModel.setSetpoint(newVal)
                                                },
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                enabled = isConnected
                                            ) {
                                                Text("+", fontSize = 18.sp)
                                            }
                                        }
                                    }

                                    // Weight setpoint
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Weight",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = String.format("%.1fg", liveWeight),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = { viewModel.tare() },
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                enabled = isConnected
                                            ) {
                                                Text("T", fontSize = 14.sp)
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    val newVal = targetWeight - 1f
                                                    viewModel.setWeight(newVal)
                                                },
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                enabled = isConnected
                                            ) {
                                                Text("-", fontSize = 18.sp)
                                            }
                                            FilledTonalButton(
                                                onClick = {
                                                    val newVal = targetWeight + 1f
                                                    viewModel.setWeight(newVal)
                                                },
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                enabled = isConnected
                                            ) {
                                                Text("+", fontSize = 18.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }



                        // Real-time brew chart
                        if (chartData.isNotEmpty()) {
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = stringResource(R.string.dashboard_live_chart),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        BrewChartView(
                                            dataPoints = chartData,
                                            modifier = Modifier.fillMaxWidth(),
                                            height = 200.dp
                                        )
                                    }
                                }
                            }
                        }

                        // Gauges row
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                GaugeView(
                                    value = directSensorT ?: 0f,
                                    maxValue = 110f,
                                    label = stringResource(R.string.dashboard_boiler_t),
                                    unit = "\u00B0C",
                                    gaugeColor = GaugeTemperature
                                )
                                GaugeView(
                                    value = 0f,
                                    maxValue = 160f,
                                    label = stringResource(R.string.dashboard_steam_t),
                                    unit = "\u00B0C",
                                    gaugeColor = GaugeFlow
                                )
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                GaugeView(
                                    value = directSensorP ?: 0f,
                                    maxValue = 12f,
                                    label = "Pressure",
                                    unit = "bar",
                                    gaugeColor = GaugePressure
                                )
                                GaugeView(
                                    value = 0f,
                                    maxValue = 5f,
                                    label = "Flow Rate",
                                    unit = "ml/s",
                                    gaugeColor = GaugeFlow
                                )
                            }
                        }

                        // Brew info cards
                        item {
                            if (directBrewActive == true) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                            MetricItem(
                                                label = stringResource(R.string.dashboard_time),
                                                value = "--"
                                            )
                                            MetricItem(
                                                label = stringResource(R.string.dashboard_volume),
                                                value = String.format("%.0f ml", 0f)
                                            )
                                            MetricItem(
                                                label = stringResource(R.string.dashboard_pump),
                                                value = String.format("%.0f%%", 0f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Historical/shots info
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.dashboard_machine_stats),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        MetricItem(
                                            label = stringResource(R.string.dashboard_shots_today),
                                            value = "--"
                                        )
                                        MetricItem(
                                            label = "Setpoint",
                                            value = String.format("%.0f\u00B0C", directSensorTT ?: 0f)
                                        )
                                    }
                                    // Phase info requires WebSocket
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

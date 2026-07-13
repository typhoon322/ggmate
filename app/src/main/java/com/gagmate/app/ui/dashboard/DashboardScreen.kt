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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val machineState by viewModel.machineState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val chartData by viewModel.chartData.collectAsState()

    var steamOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.startPolling()
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopPolling() }
    }

    // Sync steam status from machine state
    LaunchedEffect(machineState?.steamStatus) {
        steamOn = machineState?.steamStatus == "on"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "GagMate",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            isConnected = isConnected,
                            label = if (isConnected) "Connected" else "Connecting..."
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                        if (machineState?.isActive == true) viewModel.stopBrew()
                        else viewModel.startBrew()
                    },
                    containerColor = if (machineState?.isActive == true)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = if (machineState?.isActive == true)
                            Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (machineState?.isActive == true) "Stop" else "Start Brew",
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
                isLoading && machineState == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                error != null && machineState == null -> {
                    // No data yet - show connection error
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Cannot Connect",
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
                            Text("Configure Connection")
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MachineStatusBadge(
                                    status = machineState?.status ?: "idle",
                                    isActive = machineState?.isActive == true
                                )
                                if (machineState?.activeProfileName?.isNotBlank() == true) {
                                    Text(
                                        text = machineState!!.activeProfileName,
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
                                        text = "Machine Controls",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Flush button
                                        OutlinedButton(
                                            onClick = { viewModel.flush() },
                                            modifier = Modifier.weight(1f),
                                            enabled = isConnected && machineState?.isActive != true
                                        ) {
                                            Icon(
                                                Icons.Default.WaterDrop,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Flush", maxLines = 1)
                                        }

                                        // Steam toggle
                                        Button(
                                            onClick = {
                                                val newState = !steamOn
                                                steamOn = newState
                                                viewModel.toggleSteam(newState)
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = if (steamOn)
                                                ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF78909C)
                                                )
                                            else
                                                ButtonDefaults.buttonColors(),
                                            enabled = isConnected && machineState?.isActive != true
                                        ) {
                                            Icon(
                                                Icons.Default.Thermostat,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                if (steamOn) "Steam ON" else "Steam OFF",
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    // Temperature setpoint
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Setpoint:",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = String.format("%.0f°C", machineState?.setpoint ?: 0f),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilledTonalButton(
                                                onClick = {
                                                    val newVal = (machineState?.setpoint ?: 93f) - 1f
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
                                                    val newVal = (machineState?.setpoint ?: 93f) + 1f
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
                                }
                            }
                        }

                        // Prime pump button
                        item {
                            OutlinedButton(
                                onClick = { viewModel.primePump() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = isConnected && machineState?.isActive != true
                            ) {
                                Icon(
                                    Icons.Default.Water,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Prime Pump (Fill System)")
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
                                            text = "Live Brew Chart",
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
                                    value = machineState?.temperature ?: 0f,
                                    maxValue = 110f,
                                    label = "Boiler T",
                                    unit = "\u00B0C",
                                    gaugeColor = GaugeTemperature
                                )
                                GaugeView(
                                    value = machineState?.steamTemperature ?: 0f,
                                    maxValue = 160f,
                                    label = "Steam T",
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
                                    value = machineState?.pressure ?: 0f,
                                    maxValue = 12f,
                                    label = "Pressure",
                                    unit = "bar",
                                    gaugeColor = GaugePressure
                                )
                                GaugeView(
                                    value = machineState?.flow ?: 0f,
                                    maxValue = 5f,
                                    label = "Flow Rate",
                                    unit = "ml/s",
                                    gaugeColor = GaugeFlow
                                )
                            }
                        }

                        // Brew info cards
                        item {
                            if (machineState?.isActive == true) {
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
                                            text = "Active Brew",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            MetricItem(
                                                label = "Time",
                                                value = machineState?.brewTimeFormatted ?: "0s"
                                            )
                                            MetricItem(
                                                label = "Volume",
                                                value = String.format("%.0f ml", machineState?.shotVolume ?: 0f)
                                            )
                                            MetricItem(
                                                label = "Pump",
                                                value = String.format("%.0f%%", machineState?.pumpOutput ?: 0f)
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
                                        text = "Machine Stats",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        MetricItem(
                                            label = "Shots Today",
                                            value = "${machineState?.shotNumber ?: 0}"
                                        )
                                        MetricItem(
                                            label = "Setpoint",
                                            value = String.format("%.0f\u00B0C", machineState?.setpoint ?: 0f)
                                        )
                                    }
                                    if (machineState?.currentPhaseName?.isNotBlank() == true) {
                                        Text(
                                            text = "Phase: ${machineState!!.currentPhaseName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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

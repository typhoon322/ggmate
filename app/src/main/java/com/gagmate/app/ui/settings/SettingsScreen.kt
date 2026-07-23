package com.gagmate.app.ui.settings
import com.gagmate.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.BuildConfig
import com.gagmate.app.LocaleHelper
import com.gagmate.app.data.system.DebugLogState
import com.gagmate.app.ui.components.PageHeader
import com.gagmate.app.ui.components.WsOverlayControl
import com.gagmate.app.ui.settings.SettingsViewModel.ConnectionStatus
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onBack: () -> Unit = {}
) {
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val currentLang by viewModel.currentLang.collectAsState()
    val context = LocalContext.current
    val savedMessage by viewModel.savedMessage.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val syncResult by viewModel.syncResult.collectAsState()

    val hasLog = viewModel.hasLog()
    val logSize = remember { derivedStateOf { viewModel.logSizeFormatted() } }

    LaunchedEffect(Unit) {
        viewModel.loadSettings()
        viewModel.initLanguage(context)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── Pinned page header (fixed below the system status bar) ──
            PageHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            )

            // ── Scrollable body (strictly below the pinned header) ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

            // Connection section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_connection),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    OutlinedTextField(
                        value = host,
                        onValueChange = { viewModel.updateHost(it) },
                        label = { Text(stringResource(R.string.settings_host)) },
                        placeholder = { Text("192.168.0.186") },
                        leadingIcon = { Icon(Icons.Default.WifiFind, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = { viewModel.updatePort(it) },
                        label = { Text(stringResource(R.string.settings_port)) },
                        placeholder = { Text("80") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.testConnection() },
                            modifier = Modifier.weight(1f)
                        ) {
                            when (connectionStatus) {
                                ConnectionStatus.Testing -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_testing))
                                }
                                ConnectionStatus.Connected -> {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_connected))
                                }
                                ConnectionStatus.Failed -> {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.settings_test_failed))
                                }
                                else -> {
                                    Text(stringResource(R.string.settings_test))
                                }
                            }
                        }

                        Button(
                            onClick = { viewModel.saveAndApply() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_save_apply))
                        }
                    }

                    // Sync button
                    Divider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.triggerSync() },
                            modifier = Modifier.weight(1f),
                            enabled = !isSyncing
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Sync Now")
                        }

                    }

                    // Sync result  
                    syncResult?.let { result ->
                        Text(
                            text = result.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.isSuccess)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        if (result.errors.isNotEmpty()) {
                            result.errors.forEach { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Language section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Language",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.availableLanguages.forEach { lang ->
                            FilterChip(
                                selected = currentLang == lang.code,
                                onClick = {
                                    if (currentLang != lang.code) {
                                        viewModel.setLanguage(context, lang.code)
                                    }
                                },
                                label = { Text(lang.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // About section
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.settings_version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_build_time, BuildConfig.BUILD_TIME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "A companion app for Gagguino coffee machines. Connects to ggboard to monitor and control your espresso machine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    
                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // Crash log export
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Crash Log",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (viewModel.hasCrashLog()) {
                                OutlinedButton(
                                    onClick = { viewModel.shareCrashLog(context) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.clearCrashLog() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }

                    // Network log export
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_network_log),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (hasLog) {
                                Text(
                                    text = logSize.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (hasLog) {
                                OutlinedButton(
                                    onClick = { viewModel.shareLog(context) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.clearLog() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            // WS Debug Overlay toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "WS Data Overlay",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Semi-transparent floating overlay showing parsed WS data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = WsOverlayControl.enabled,
                        onCheckedChange = { WsOverlayControl.enabled = it }
                    )
                }
            }

            // Connection info
            if (connectionStatus == ConnectionStatus.Connected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Connected to ggboard at $host:$port",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            } // end scroll body
        }
    }

    // Save confirmation snackbar
    savedMessage?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearMessage()
        }
    }
}

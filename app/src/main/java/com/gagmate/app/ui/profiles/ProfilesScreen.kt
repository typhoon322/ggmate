package com.gagmate.app.ui.profiles
import androidx.compose.ui.res.stringResource
import com.gagmate.app.R

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.local.entity.SyncStatus
import androidx.compose.runtime.collectAsState
import com.gagmate.app.ui.components.PageHeader
import com.gagmate.app.ui.components.PhaseIndicator
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.ui.components.ProfileCard
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = viewModel(),
    onOpenProfile: (String) -> Unit = {}
) {
    val profiles by viewModel.profiles.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val error by viewModel.error.collectAsState()
    val pendingUploadCount by viewModel.pendingUploadCount.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

    val context = LocalContext.current
    var showImportError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteJsonText by remember { mutableStateOf("") }

    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.importProfileFromJson(context, it)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    jsonImportLauncher.launch("application/json")
                },
                icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_import)) }
            )
        }
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
                title = {
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = { viewModel.loadProfiles() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.dashboard_refresh))
                    }
                    IconButton(onClick = {
                        jsonImportLauncher.launch("application/json")
                    }) {
                        Icon(Icons.Default.FileOpen, contentDescription = stringResource(R.string.profiles_import))
                    }
                    IconButton(onClick = { showPasteDialog = true }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.profiles_paste))
                    }
                }
            )

            when {
                profiles.isEmpty() && !isSyncing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.profiles_no_profiles),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.profiles_import_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            jsonImportLauncher.launch("application/json")
                        }) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profiles_import))
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(profiles, key = { it.name + it.author }) { profile ->
                            ProfileCard(
                                profile = profile,
                                isActive = false,
                                onClick = {
                                    onOpenProfile(profile.id)
                                },
                                onEdit = {
                                    onOpenProfile(profile.id)
                                },
                                onExport = {
                                    val json = viewModel.exportProfileAsJson(profile)
                                    // This would ideally use a share intent
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        type = "application/json"
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Export Profile")
                                    )
                                },
                                onDelete = {
                                    viewModel.deleteProfile(profile.id)
                                }
                            )
                        }

                        // Sample profile creation button
                        item {
                            OutlinedButton(
                                onClick = {
                                    val sample = viewModel.createSampleProfile()
                                    onOpenProfile(sample.id)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.profiles_create_sample))
                            }
                        }

                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }
                }
            }

            // Error snackbar
            if (error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.loadProfiles() }) {
                            Text(stringResource(R.string.history_retry))
                        }
                    }
                ) {
                    Text(error ?: "")
                }
            }

            // Paste JSON dialog
            if (showPasteDialog) {
                PasteJsonDialog(
                    jsonText = pasteJsonText,
                    onJsonTextChange = { pasteJsonText = it },
                    onConfirm = {
                        viewModel.importProfileFromJsonString(pasteJsonText)
                        showPasteDialog = false
                        pasteJsonText = ""
                        viewModel.loadProfiles()
                    },
                    onDismiss = {
                        showPasteDialog = false
                        pasteJsonText = ""
                    }
                )
            }

            // Import error dialog
            if (showImportError) {
                AlertDialog(
                    onDismissRequest = { showImportError = false },
                    title = { Text(stringResource(R.string.profiles_import_failed)) },
                    text = { Text(errorMessage) },
                    confirmButton = {
                        TextButton(onClick = { showImportError = false }) {
                            Text(stringResource(R.string.profiles_close))
                        }
                    }
                )
            }
        }
    }

    // Toast when fresh data arrives from a background sync (no blocking loading UI).
    LaunchedEffect(syncMessage) {
        if (syncMessage != null) {
            Toast.makeText(context, syncMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearSyncMessage()
        }
    }

    // Error snackbar
    LaunchedEffect(error) {
        if (error != null) {
            kotlinx.coroutines.delay(4000)
            viewModel.clearError()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteJsonDialog(
    jsonText: String,
    onJsonTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Profile JSON") },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Paste your Gagguino profile JSON below. It will be saved as a .json file and imported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = onJsonTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    placeholder = { Text("{ \"name\": \"My Profile\", \"phases\": [...] }") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    maxLines = Int.MAX_VALUE
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = jsonText.isNotBlank()
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profiles_cancel))
            }
        }
    )
}

package com.gagmate.app.ui.profiles
import androidx.compose.ui.res.stringResource
import com.gagmate.app.R

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gagmate.app.data.model.ShotProfile
import com.gagmate.app.ui.components.PhaseIndicator
import com.gagmate.app.ui.components.ProfileCard
import com.gagmate.app.data.model.BrewPhase
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()

    val context = LocalContext.current
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<ShotProfile?>(null) }
    var showImportError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ShotProfile?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteJsonText by remember { mutableStateOf("") }

    val jsonImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val result = viewModel.importProfileFromJson(context, it)
            if (result == null) {
                errorMessage = context.getString(R.string.profiles_parse_error)
                showImportError = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Profiles",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    jsonImportLauncher.launch("application/json")
                },
                icon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                text = { Text(stringResource(R.string.profiles_import)) }
            )
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
                profiles.isEmpty() && !isLoading -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(profiles, key = { it.name + it.author }) { profile ->
                            ProfileCard(
                                profile = profile,
                                isActive = profile.profileId == activeProfileId,
                                onClick = {
                                    selectedProfile = profile
                                    showDetailDialog = true
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
                                    profile.profileId?.let { viewModel.deleteProfile(it) }
                                }
                            )
                        }

                        // Sample profile creation button
                        item {
                            OutlinedButton(
                                onClick = {
                                    val sample = viewModel.createSampleProfile()
                                    selectedProfile = sample
                                    showDetailDialog = true
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
                        .align(Alignment.BottomCenter)
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
                        val count = viewModel.importProfileFromJsonString(context, pasteJsonText)
                        when {
                            count <= 0 -> {
                                errorMessage = context.getString(R.string.profiles_parse_error_multiple)
                                showImportError = true
                            }
                            count > 1 -> {
                                errorMessage = context.getString(R.string.profiles_imported_count, count)
                                showImportError = true
                                viewModel.loadProfiles()
                            }
                        }
                        showPasteDialog = false
                        pasteJsonText = ""
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

    // Profile detail dialog
    if (showDetailDialog && selectedProfile != null) {
        ProfileDetailDialog(
            profile = selectedProfile!!,
            onDismiss = { showDetailDialog = false },
            onEdit = {
                showDetailDialog = false
                editingProfile = selectedProfile
                showEditDialog = true
            }
        )
    }

    // Profile edit dialog
    if (showEditDialog && editingProfile != null) {
        ProfileEditDialog(
            profile = editingProfile!!,
            onDismiss = { showEditDialog = false },
            onSave = { edited ->
                showEditDialog = false
                // Upload the edited profile
                val json = viewModel.exportProfileAsJson(edited)
                val result = viewModel.importProfileFromJsonString(context, json)
                if (result == null) {
                    errorMessage = context.getString(R.string.profiles_save_failed)
                    showImportError = true
                }
            }
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditDialog(
    profile: ShotProfile,
    onDismiss: () -> Unit,
    onSave: (ShotProfile) -> Unit
) {
    var editedName by remember { mutableStateOf(profile.name) }
    var editedAuthor by remember { mutableStateOf(profile.author) }
    var editedNotes by remember { mutableStateOf(profile.notes) }
    var editedPhases by remember { mutableStateOf(profile.phases) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Profile: ${profile.name}") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text(stringResource(R.string.profile_edit_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedAuthor,
                            onValueChange = { editedAuthor = it },
                            label = { Text(stringResource(R.string.profile_edit_author)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedNotes,
                            onValueChange = { editedNotes = it },
                            label = { Text(stringResource(R.string.profile_edit_notes)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            maxLines = 5
                        )
                    }

                    items(editedPhases.indices.toList()) { idx ->
                    val phase = editedPhases[idx]
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = phase.name,
                                    onValueChange = { newName ->
                                        editedPhases = editedPhases.toMutableList().apply {
                                            set(idx, phase.copy(name = newName))
                                        }
                                    },
                                    label = { Text(stringResource(R.string.profile_edit_phase_name)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                IconButton(onClick = {
                                    editedPhases = editedPhases.toMutableList().apply { removeAt(idx) }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.profile_edit_remove), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = phase.target.toString(),
                                    onValueChange = { newVal ->
                                        val parsed = newVal.toFloatOrNull() ?: phase.target
                                        editedPhases = editedPhases.toMutableList().apply {
                                            set(idx, phase.copy(target = parsed))
                                        }
                                    },
                                    label = { Text("Target (${if (phase.isPressureType) "bar" else "ml/s"})") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                                OutlinedTextField(
                                    value = phase.time.toString(),
                                    onValueChange = { newVal ->
                                        val parsed = newVal.toFloatOrNull() ?: phase.time
                                        editedPhases = editedPhases.toMutableList().apply {
                                            set(idx, phase.copy(time = parsed))
                                        }
                                    },
                                    label = { Text(stringResource(R.string.profile_edit_time)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                )
                            }
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = {
                            editedPhases = editedPhases + BrewPhase(
                                name = "Phase ${editedPhases.size + 1}",
                                target = 0f,
                                time = 0f
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_edit_add_phase))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(profile.copy(name = editedName, author = editedAuthor, notes = editedNotes, phases = editedPhases))
            }) {
                Text(stringResource(R.string.profiles_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profiles_cancel))
            }
        }
    )
}

@Composable
private fun ProfileDetailDialog(
    profile: ShotProfile,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = profile.name, style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    if (profile.author.isNotBlank()) {
                        Text(
                            text = "by ${profile.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.notes.isNotBlank()) {
                        Text(
                            text = profile.notes,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Brew Phases (${profile.phaseCount})",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(profile.phases) { phase ->
                    PhaseIndicator(phase = phase)
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Total: ${profile.totalBrewTime}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profiles_close))
            }
        }
    )
}

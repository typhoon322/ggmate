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
import com.gagmate.app.ui.components.BrewChartView
import com.gagmate.app.ui.components.ChartPoint
import androidx.compose.runtime.collectAsState
import com.gagmate.app.ui.components.PhaseIndicator
import com.gagmate.app.ui.components.ProfileCard
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.PhaseV3
import com.gagmate.app.data.model.PhaseTarget
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel = viewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val error by viewModel.error.collectAsState()
    val pendingUploadCount by viewModel.pendingUploadCount.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()

    val context = LocalContext.current
    var showDetailDialog by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var showImportError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
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
                                isActive = false,
                                onClick = {
                                    selectedProfile = profile
                                    showDetailDialog = true
                                },
                                onEdit = {
                                    editingProfile = profile
                                    showEditDialog = true
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
                                    profile.machineProfileId?.let { viewModel.deleteProfile(it) }
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
                        viewModel.importProfileFromJsonString(pasteJsonText)
                        showPasteDialog = false
                        pasteJsonText = ""
                        viewModel.loadProfiles()
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
                viewModel.saveEditedProfile(edited)
            }
        )
    }

    // Sync result message banner
    LaunchedEffect(syncMessage) {
        if (syncMessage != null) {
            kotlinx.coroutines.delay(3000)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditDialog(
    profile: ProfileEntity,
    onDismiss: () -> Unit,
    onSave: (ProfileEntity) -> Unit
) {
    var editedName by remember { mutableStateOf(profile.name) }
    var editedAuthor by remember { mutableStateOf(profile.author) }
    var editedNotes by remember { mutableStateOf(profile.notes) }
    var editedPhases by remember {
        val phases: List<com.gagmate.app.data.model.BrewPhase> = try {
            profile.toShotProfile().phases
        } catch (_: Exception) { emptyList() }
        mutableStateOf(phases.toMutableList())
    }

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
                            // Phase type selector
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Type:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FilterChip(
                                    selected = phase.type == "pressure",
                                    onClick = {
                                        editedPhases = editedPhases.toMutableList().apply {
                                            set(idx, phase.copy(type = "pressure"))
                                        }
                                    },
                                    label = { Text("Pressure", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = phase.type == "flow",
                                    onClick = {
                                        editedPhases = editedPhases.toMutableList().apply {
                                            set(idx, phase.copy(type = "flow"))
                                        }
                                    },
                                    label = { Text("Flow", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
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
                                    label = { Text("Target (${if (phase.type == "pressure") "bar" else "ml/s"})") },
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
                            editedPhases = editedPhases.toMutableList().apply {
                                add(BrewPhase(
                                    name = "Phase ${this.size + 1}",
                                    target = 0f,
                                    time = 0f
                                ))
                            }
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
                onSave(profile.copy(name = editedName, author = editedAuthor, notes = editedNotes, phasesJson = com.google.gson.Gson().toJson(editedPhases)))
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

/** Generate chart data points from brew phases for profile visualization. */
private fun generateProfileChartPoints(phases: List<BrewPhase>, resolution: Float = 0.25f): List<ChartPoint> {
    if (phases.isEmpty()) return emptyList()
    val points = mutableListOf<ChartPoint>()
    var elapsed = 0f
    var pressure = 0f
    var flow = 0f

    for (phase in phases) {
        val duration = phase.time.coerceAtLeast(0.1f)
        var t = 0f
        while (t < duration) {
            when (phase.type) {
                "pressure" -> pressure = phase.target
                "flow" -> flow = phase.target
            }
            points.add(ChartPoint(
                time = elapsed + t,
                pressure = pressure,
                flowRate = flow
            ))
            t = (t + resolution).coerceAtMost(duration)
        }
        elapsed += duration
    }
    return points
}

@Composable
private fun ProfileDetailDialog(
    profile: ProfileEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = profile.name, style = MaterialTheme.typography.headlineSmall)
        },
        text = {
            val phasesList = try {
                profile.toShotProfile().phases.takeIf { it.isNotEmpty() }
            } catch (_: Exception) { null }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Profile chart
                item {
                    val chartPoints = phasesList?.let { generateProfileChartPoints(it) } ?: emptyList()
                    if (chartPoints.isNotEmpty()) {
                        BrewChartView(
                            dataPoints = chartPoints,
                            modifier = Modifier.fillMaxWidth(),
                            height = 160.dp
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

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
                }

                if (phasesList != null && phasesList.isNotEmpty()) {
                    item {
                        Text(
                            text = "Phases",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    itemsIndexed(phasesList) { index, phase ->
                        PhaseCard(index = index + 1, phase = phase.toPhaseV3())
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.profiles_edit))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.profiles_close))
                }
            }
        }
    )
}

private fun BrewPhase.toPhaseV3(): PhaseV3 = PhaseV3(
    name = name,
    type = type.uppercase(),
    target = PhaseTarget(end = target, time = (time * 1000).toInt()),
    skip = false
)

@Composable
private fun PhaseCard(index: Int, phase: PhaseV3) {
    val targetText = if (phase.target != null) {
        val t = phase.target!!
        val unit = if (phase.type == "FLOW") " ml/s" else " bar"
        "${t.end}$unit in ${t.time / 1000}s" + 
        (if (t.start != null) " (from ${t.start})" else "") +
        " ${t.curve}"
    } else ""
    val stopText = listOfNotNull(
        phase.stopConditions?.time?.let { "${it / 1000}s" },
        phase.stopConditions?.pressureAbove?.let { ">${it}bar" },
        phase.stopConditions?.pressureBelow?.let { "<${it}bar" },
        phase.stopConditions?.waterPumpedInPhase?.let { "${it}ml" }
    ).joinToString(" ")
    Card(
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Phase $index: ${phase.name}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Type: ${phase.type}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (targetText.isNotBlank()) {
                Text(
                    text = "Target: $targetText",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (stopText.isNotBlank()) {
                Text(
                    text = "Stop: $stopText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

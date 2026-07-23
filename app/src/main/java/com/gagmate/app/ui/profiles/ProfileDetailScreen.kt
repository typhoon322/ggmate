package com.gagmate.app.ui.profiles

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.ActivityInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gagmate.app.BuildConfig
import com.gagmate.app.R
import com.gagmate.app.data.local.entity.ProfileEntity
import com.gagmate.app.data.model.BrewPhase
import com.gagmate.app.data.model.PhaseTarget
import com.gagmate.app.data.model.PhaseV3
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.ui.components.BrewChartView
import com.gagmate.app.ui.components.ChartPoint
import com.gagmate.app.ui.components.generateProfileChartPoints
import com.google.gson.Gson
import kotlinx.coroutines.launch

/**
 * Standalone profile detail page.
 *
 * On entering edit mode the edit controls are shown directly under the chart
 * (no dialog). Phase/target edits update the chart live. Pressing "Save" pushes
 * the profile to the machine; only after the machine confirms (HTTP 200) is the
 * change written to the local DB. "Cancel" discards everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileDetailScreen(
    profileId: String,
    onBack: () -> Unit
) {
    val vm: ProfilesViewModel = viewModel()
    val profile by AppContainer.profileRepo.observeProfile(profileId).collectAsState(initial = null)

    LaunchedEffect(profileId) {
        if (BuildConfig.DEBUG) Log.d("GagMateProfile", "Detail open profileId='$profileId'")
    }

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        if (profile == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        ProfileDetailContent(
            profile = profile!!,
            padding = padding,
            onBack = onBack,
            onSave = { vm.pushAndSaveIfConfirmed(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailContent(
    profile: ProfileEntity,
    padding: PaddingValues,
    onBack: () -> Unit,
    onSave: suspend (ProfileEntity) -> Result<Unit>
) {
    val phasesFromJson = remember(profile.phasesJson) {
        try { profile.toShotProfile().phases } catch (_: Exception) { emptyList() }
    }
    val hasNoPhases = phasesFromJson.isEmpty() && profile.machineProfileId != null
    var loadingPhases by remember(profile.id) { mutableStateOf(hasNoPhases) }

    // ── Edit state (kept in a local copy so the chart updates live) ──
    var isEditing by remember { mutableStateOf(false) }
    var editedName by remember(profile.id) { mutableStateOf(profile.name) }
    var editedAuthor by remember(profile.id) { mutableStateOf(profile.author) }
    var editedNotes by remember(profile.id) { mutableStateOf(profile.notes) }
    var editedPhases by remember(profile.id) { mutableStateOf(phasesFromJson.toMutableList()) }

    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveFailedText = stringResource(R.string.profiles_save_failed)

    val phasesList = phasesFromJson.takeIf { it.isNotEmpty() }

    // Current phase targets come LIVE from the machine over WebSocket
    // (g_prof → d_prof). This firmware does not expose the REST profile-detail
    // endpoint, so the authoritative "now" recipe is only available this way.
    // The local DB (phasesJson) and the latest shot's embedded profile are used
    // as fallbacks by the chart (phasesList) when the live request yields nothing.
    var fetchedPhases by remember(profile.id) { mutableStateOf<List<BrewPhase>>(emptyList()) }
    LaunchedEffect(profile.machineProfileId, profile.name) {
        if (BuildConfig.DEBUG)
            Log.d(
                "GagMateProfile",
                "Detail content: name='${profile.name}' machineProfileId=${profile.machineProfileId} " +
                    "phasesJsonLen=${profile.phasesJson.length} phasesFromJson=${phasesFromJson.size} hasNoPhases=$hasNoPhases"
            )
        try {
            fetchedPhases = AppContainer.machineRepo.fetchProfilePhases(profile.machineProfileId, profile.name)
        } catch (_: Exception) { }
        // Stop the loading shimmer once we've attempted the fetch (even if empty).
        loadingPhases = false
    }

    // Chart reads from the live editing copy while editing, otherwise from the
    // REST-fetched phases (falling back to the DB phases).
    val chartPhases = if (isEditing) editedPhases else (fetchedPhases.takeIf { it.isNotEmpty() } ?: phasesList)

    fun discardEdits() {
        editedName = profile.name
        editedAuthor = profile.author
        editedNotes = profile.notes
        editedPhases = phasesFromJson.toMutableList()
        saveError = null
        isEditing = false
    }

    fun doSave() {
        saving = true
        saveError = null
        scope.launch {
            val edited = profile.copy(
                name = editedName,
                author = editedAuthor,
                notes = editedNotes,
                phasesJson = Gson().toJson(editedPhases)
            )
            val res = onSave(edited)
            saving = false
            if (res.isSuccess) {
                isEditing = false
            } else {
                saveError = res.exceptionOrNull()?.message ?: saveFailedText
                if (BuildConfig.DEBUG)
                    Log.w("GagMateProfile", "Save rejected by machine: ${saveError}")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(padding)) {
        // Header: back + name + (edit | save/cancel)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.settings_back), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isEditing) editedName else profile.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (isEditing) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    TextButton(onClick = { doSave() }) {
                        Text(stringResource(R.string.profiles_save), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { discardEdits() }) {
                        Text(stringResource(R.string.profiles_cancel))
                    }
                }
            } else {
                IconButton(onClick = {
                    // Start editing from the reliable REST phases if available.
                    editedPhases = (fetchedPhases.takeIf { it.isNotEmpty() } ?: phasesFromJson).toMutableList()
                    isEditing = true
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profiles_edit), modifier = Modifier.size(18.dp))
                }
            }
        }

        if (saveError != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = saveError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // Upper half: chart (updates live while editing)
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            when {
                chartPhases != null && chartPhases.isNotEmpty() -> {
                    val chartPoints = remember(chartPhases) { generateProfileChartPoints(chartPhases) }
                    if (chartPoints.isNotEmpty()) {
                        BrewChartView(dataPoints = chartPoints, modifier = Modifier.fillMaxSize(), height = Dp.Unspecified)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.profile_no_phases),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                loadingPhases && !isEditing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_fetching_phases), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.profile_no_phases),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Lower half: editor (when editing) or read-only data
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isEditing) {
                item {
                    ProfileEditPanel(
                        name = editedName,
                        author = editedAuthor,
                        notes = editedNotes,
                        phases = editedPhases,
                        onNameChange = { editedName = it },
                        onAuthorChange = { editedAuthor = it },
                        onNotesChange = { editedNotes = it },
                        onPhasesChange = { editedPhases = it.toMutableList() }
                    )
                }
            } else {
                item {
                    if (profile.author.isNotBlank()) {
                        Text(
                            text = "by ${profile.author}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (profile.notes.isNotBlank()) {
                        Text(text = profile.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (phasesList != null && phasesList.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.profile_phases),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (loadingPhases) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_fetching_phases))
                            }
                        }
                    }
                    itemsIndexed(phasesList) { index, phase ->
                        PhaseCard(index = index + 1, phase = phase.toPhaseV3())
                    }
                }
            }
        }
    }
}

/** Inline edit controls rendered directly under the chart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditPanel(
    name: String,
    author: String,
    notes: String,
    phases: List<BrewPhase>,
    onNameChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onPhasesChange: (List<BrewPhase>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.profile_edit_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = author,
            onValueChange = onAuthorChange,
            label = { Text(stringResource(R.string.profile_edit_author)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.profile_edit_notes)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
            maxLines = 5
        )

        Text(
            text = stringResource(R.string.profile_phases),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        phases.forEachIndexed { idx, phase ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = phase.name,
                            onValueChange = { newName ->
                                onPhasesChange(phases.toMutableList().apply { set(idx, phase.copy(name = newName)) })
                            },
                            label = { Text(stringResource(R.string.profile_edit_phase_name)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            onPhasesChange(phases.toMutableList().apply { removeAt(idx) })
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.profile_edit_remove), tint = MaterialTheme.colorScheme.error)
                        }
                    }
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
                            onClick = { onPhasesChange(phases.toMutableList().apply { set(idx, phase.copy(type = "pressure")) }) },
                            label = { Text("Pressure", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = phase.type == "flow",
                            onClick = { onPhasesChange(phases.toMutableList().apply { set(idx, phase.copy(type = "flow")) }) },
                            label = { Text("Flow", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = phase.target.toString(),
                            onValueChange = { newVal ->
                                val parsed = newVal.toFloatOrNull() ?: phase.target
                                onPhasesChange(phases.toMutableList().apply { set(idx, phase.copy(target = parsed)) })
                            },
                            label = { Text(if (phase.type == "pressure") stringResource(R.string.profile_edit_target_bar) else stringResource(R.string.profile_edit_target_mls)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                        OutlinedTextField(
                            value = phase.time.toString(),
                            onValueChange = { newVal ->
                                val parsed = newVal.toFloatOrNull() ?: phase.time
                                onPhasesChange(phases.toMutableList().apply { set(idx, phase.copy(time = parsed)) })
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

        OutlinedButton(
            onClick = {
                onPhasesChange(phases.toMutableList().apply { add(BrewPhase(name = "Phase ${this.size + 1}", target = 0f, time = 0f)) })
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.profile_edit_add_phase))
        }
    }
}

fun BrewPhase.toPhaseV3(): PhaseV3 = PhaseV3(
    name = name,
    type = type.uppercase(),
    target = PhaseTarget(end = target, time = (time * 1000).toInt()),
    skip = false
)

@Composable
fun PhaseCard(index: Int, phase: PhaseV3) {
    val targetText = if (phase.target != null) {
        val t = phase.target!!
        val unit = if (phase.type == "FLOW") " ml/s" else " bar"
        "${String.format("%.1f", t.end)}$unit in ${t.time / 1000}s" +
            (if (t.start != null) " (from ${String.format("%.1f", t.start)})" else "") +
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

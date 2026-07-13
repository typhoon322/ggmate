package com.gagmate.app.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.data.api.ShotRecord
import com.gagmate.app.ui.components.BrewChartView
import com.gagmate.app.ui.components.ChartPoint
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotHistoryScreen(
    viewModel: ShotHistoryViewModel = viewModel()
) {
    val shots by viewModel.shots.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var expandedShotId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadShots()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shot History") },
                actions = {
                    IconButton(onClick = { viewModel.loadShots() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
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
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                shots.isEmpty() && !isLoading -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Coffee,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No Shot History", style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Completed brew shots will appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shots, key = { it.id }) { shot ->
                            ShotHistoryCard(
                                shot = shot,
                                isExpanded = expandedShotId == shot.id,
                                onToggle = {
                                    expandedShotId = if (expandedShotId == shot.id) null else shot.id
                                },
                                onDelete = { viewModel.deleteShot(shot.id) },
                                onExport = {
                                    val json = viewModel.exportShotAsJson(shot)
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        type = "application/json"
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, "Export Shot Data")
                                    )
                                }
                            )
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }

            if (error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.loadShots() }) {
                            Text("Retry")
                        }
                    }
                ) { Text(error ?: "") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShotHistoryCard(
    shot: ShotRecord,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Coffee, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(shot.profile.ifEmpty { "Espresso" },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                        Text(dateFormat.format(Date(shot.timestamp * 1000)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format("%.1fs", shot.duration),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (shot.volume > 0) {
                            Text(String.format("%.0fml", shot.volume),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onExport, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Expanded replay section
            if (isExpanded && shot.data.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(12.dp))
                ShotReplaySection(shot = shot)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShotReplaySection(shot: ShotRecord) {
    val chartPoints = remember(shot) {
        shot.data.map { ChartPoint(time = it.time, pressure = it.pressure, flowRate = it.flow) }
    }
    val totalTime = chartPoints.lastOrNull()?.time ?: 0f
    val maxTime = totalTime.coerceAtLeast(1f)

    var currentTime by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }

    // Animation loop
    LaunchedEffect(isPlaying, speed) {
        if (isPlaying && totalTime > 0) {
            while (currentTime < totalTime) {
                delay(16L) // ~60fps
                currentTime = (currentTime + 0.016f * speed).coerceAtMost(totalTime)
            }
            isPlaying = false
            currentTime = totalTime
        }
    }

    // Reset when shot changes
    LaunchedEffect(shot) {
        currentTime = 0f
        isPlaying = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Shot Replay", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)

        BrewChartView(
            dataPoints = chartPoints,
            progressTime = currentTime,
            timeWindow = maxTime.coerceAtLeast(30f),
            height = 180.dp,
            modifier = Modifier.fillMaxWidth()
        )

        // Seekbar
        Slider(
            value = currentTime,
            onValueChange = { currentTime = it.coerceAtMost(maxTime) },
            valueRange = 0f..maxTime,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )

        // Controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play/pause + speed
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { isPlaying = !isPlaying }) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }

                // Speed selector
                listOf(1f, 2f, 4f, 8f).forEach { s ->
                    val isSelected = speed == s
                    FilterChip(
                        selected = isSelected,
                        onClick = { speed = s },
                        label = { Text("${s.toInt()}x", fontSize = 12.sp) },
                        modifier = Modifier.widthIn(min = 44.dp),
                        leadingIcon = if (s > 1f) {
                            { Icon(Icons.Default.FastForward, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        } else null
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }

            // Time display
            Text(
                text = "${String.format("%.1f", currentTime)}s / ${String.format("%.1f", totalTime)}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

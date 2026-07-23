package com.gagmate.app.ui.dashboard

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.pm.ActivityInfo
import com.gagmate.app.R
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.ui.components.BrewChartView

/**
 * Full-screen, landscape "curve mode" shown automatically when a brew starts
 * on the machine. Streams the live [com.gagmate.app.data.repository.ShotRepository.chartData]
 * buffer (pressure / flow / temperature / weight) in real time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCurveScreen(
    onBack: () -> Unit = {}
) {
    val chartData by AppContainer.shotRepo.chartData.collectAsState()
    val isBrewing by AppContainer.machineSession.brewActive.collectAsState()

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val last = chartData.lastOrNull()

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(padding)
        ) {
            // Minimal header: back + title + brewing chip (replaces the tall TopAppBar).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.settings_back), modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Coffee, contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.live_curve_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                AssistChip(
                    onClick = { },
                    label = {
                        Text(
                            if (isBrewing) stringResource(R.string.live_curve_brewing)
                            else stringResource(R.string.live_curve_idle)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (isBrewing)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Live numeric readouts
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LiveStat(stringResource(R.string.dashboard_time), String.format("%.1fs", last?.time ?: 0f))
                LiveStat(stringResource(R.string.chart_temperature), String.format("%.1f°C", last?.temperature ?: 0f), Color(0xFFF44336))
                LiveStat(stringResource(R.string.dashboard_pressure), String.format("%.1f bar", last?.pressure ?: 0f), Color(0xFF2196F3))
                LiveStat(stringResource(R.string.chart_pump_flow), String.format("%.1f ml/s", last?.flowRate ?: 0f), Color(0xFFFFC107))
                LiveStat(stringResource(R.string.chart_weight), String.format("%.1f g", last?.shotWeight ?: 0f), Color(0xFF8D6E63))
            }

            Spacer(Modifier.height(8.dp))

            // Live chart (fills the remaining space)
            if (chartData.isNotEmpty()) {
                BrewChartView(
                    dataPoints = chartData,
                    modifier = Modifier.fillMaxWidth(),
                    height = Dp.Unspecified
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.live_curve_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveStat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

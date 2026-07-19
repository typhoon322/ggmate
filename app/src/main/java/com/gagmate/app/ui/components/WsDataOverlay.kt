package com.gagmate.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.data.protocol.*
import com.gagmate.app.data.repository.AppContainer
import com.gagmate.app.data.session.ConnectionState
import kotlinx.coroutines.flow.collectLatest

/**
 * Semi-transparent floating overlay showing parsed WS data in real-time.
 * Positioned at the top of the screen. Toggled from Settings.
 *
 * Format:
 *   [STATUS] temp°C / pressure bar / Lvl:x%  MODE:x  uptime
 *   ── last 40 messages ──
 *   d_sensor_sna → temp=30.8°C  target=94°C  pressure=-0.078bar  Lvl:100%
 *   d_sys_state → state=1  mode=7  uptime=13539s
 *   ...
 */
object WsOverlayControl {
    var enabled: Boolean by mutableStateOf(false)
}

@Composable
fun WsDataOverlay() {
    if (!WsOverlayControl.enabled) return

    val session = AppContainer.machineSession
    val connState by session.connectionState.collectAsState()
    val sensor by session.sensorSnapshot.collectAsState()
    val brewActive by session.brewActive.collectAsState()
    val mode by session.machineMode.collectAsState()

    // Real-time message log
    val messages = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        session.messages.collectLatest { msg ->
            messages.add(0, formatWsMessage(msg))
            if (messages.size > 60) messages.removeAt(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xBB1A1A2E))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        // ── Status line ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Connection indicator
            val (icon, sColor) = when (connState) {
                ConnectionState.CONNECTED -> "●" to Color(0xFF4CAF50)
                ConnectionState.CONNECTING -> "○" to Color(0xFFFFC107)
                else -> "○" to Color(0xFFF44336)
            }
            Text(
                text = icon,
                color = sColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )

            // Sensor data
            Text(
                text = "${"%.1f".format(sensor.temperature)}°C / ${"%.1f".format(sensor.pressure)}bar / Lvl:${sensor.waterLevel}%",
                color = Color(0xCCFFFFFF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            // Mode & brew state
            val modeStr = when (mode) {
                0 -> "INIT"
                1 -> "STDBY"
                2 -> "FLUSH"
                3 -> "DESCALE"
                8 -> "TARE"
                else -> "MODE:$mode"
            }
            Text(
                text = "$modeStr${if (brewActive) "☕" else ""}",
                color = if (mode == 2) Color(0xFFFFC107) else Color(0xCCFFFFFF),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            // Close button
            IconButton(
                onClick = { WsOverlayControl.enabled = false },
                modifier = Modifier.size(18.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close WS overlay",
                    tint = Color(0xAAFFFFFF),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // ── Message log (compact) ──────────────────────────────────
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(messages.take(40)) { msg ->
                Text(
                    text = msg,
                    color = Color(0xCCFFFFFF),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LaunchedEffect(messages.size) {
            listState.animateScrollToItem(0)
        }
    }
}

/** Format a ProtoMessage into a compact, human-readable one-liner. */
private fun formatWsMessage(msg: ProtoMessage): String = when (msg) {
    is SystemStateMsg -> {
        val m = msg.value
        "d_sys_state · state=${m.state} mode=${m.mode} uptime=${m.uptime}s"
    }
    is SensorSnapshotMsg -> {
        val s = msg.value
        "d_sensor_sna · ${"%.1f".format(s.temperature)}°C  →${"%.1f".format(s.targetTemperature)}°C  " +
        "${"%.3f".format(s.pressure)}bar  Lvl:${s.waterLevel}%"
    }
    is ShotSnapshotMsg -> {
        val s = msg.value
        "d_shot_snap · t=${s.timeInShot}ms  ${"%.1f".format(s.pressure)}bar  " +
        "${"%.1f".format(s.flow)}ml/s  ${"%.1f".format(s.temperature)}°C  ${"%.1f".format(s.weight)}g"
    }
    is ProfileDictMsg -> {
        val selected = msg.profiles.firstOrNull { it.isSelected }
        val suffix = if (selected != null) " [${selected.name}]" else ""
        "d_prof_dict · ${msg.profiles.size} profiles$suffix"
    }
    is ActiveProfileMsg -> {
        "d_act_prof · \"${msg.name}\"  ${msg.phases.size} phases"
    }
    is ShotHistoryIndexMsg -> {
        "d_shot_hist_index · ${msg.entries.size} entries"
    }
    is SettingsMsg -> {
        "d_settings · ${msg.values.size} keys"
    }
    is UnknownMsg -> {
        "? ${msg.command} · ${msg.payloadSize}B"
    }
    else -> ""
}

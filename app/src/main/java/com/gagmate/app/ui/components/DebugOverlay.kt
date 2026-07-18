package com.gagmate.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gagmate.app.data.system.DebugLogState
import com.gagmate.app.data.system.LogEntry

@Composable
fun DebugOverlay() {
    val entries by DebugLogState.entries.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (!DebugLogState.enabled) return

    Box(modifier = Modifier.fillMaxSize()) {
        // Floating button (bottom-right)
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = if (expanded) MaterialTheme.colorScheme.errorContainer
                           else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (expanded) MaterialTheme.colorScheme.onErrorContainer
                          else MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Debug"
            )
        }

        // Expanded log viewer
        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.6f)
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Debug Log (${entries.size})",
                            style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { DebugLogState.clear() },
                                      modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Clear",
                                     modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { expanded = false },
                                      modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close",
                                     modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Log entries
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(entries.reversed()) { entry ->
                            LogEntryRow(entry)
                        }
                    }

                    LaunchedEffect(entries.size) {
                        if (entries.isNotEmpty()) listState.animateScrollToItem(0)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val color = when {
        entry.source.startsWith("HTTP") -> Color(0xFF1A73E8)
        entry.source.startsWith("WS >>") -> Color(0xFF0D652D)
        entry.source.startsWith("WS <<") -> Color(0xFFE37400)
        entry.source.startsWith("ERR") -> Color(0xFFC5221F)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = "[${entry.timeFormatted}] ${entry.source}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = color
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

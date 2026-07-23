package com.gagmate.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Plain inline page header — this is NOT a Material [TopAppBar] and is NOT
 * placed in a Scaffold topBar slot. It renders the title + action buttons as a
 * normal row so they live *inside* the page instead of in a dedicated,
 * whitespace-heavy top bar.
 *
 * This Row carries NO top/status-bar padding on purpose. The page that uses it
 * is responsible for status-bar clearance: wrap the header + scroll body in a
 * `Column(Modifier.statusBarsPadding())` (or apply it to the scroll container)
 * so the title sits **below** the system status bar. Place [PageHeader] as the
 * FIRST, non-scrolling child so it stays pinned while the body scrolls beneath.
 */
@Composable
fun PageHeader(
    modifier: Modifier = Modifier,
    title: @Composable RowScope.() -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        navigationIcon?.invoke()
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (navigationIcon != null) Modifier.padding(start = 8.dp) else Modifier),
            verticalAlignment = Alignment.CenterVertically
        ) {
            title()
        }
        actions()
    }
}

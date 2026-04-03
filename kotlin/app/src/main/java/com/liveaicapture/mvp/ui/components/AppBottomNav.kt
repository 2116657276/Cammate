package com.liveaicapture.mvp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppRootTab(
    val label: String,
    val icon: ImageVector,
) {
    Capture("拍摄", Icons.Outlined.Tune),
    Community("社区", Icons.Outlined.People),
    Settings("设置", Icons.Outlined.Settings),
}

@Composable
fun AppBottomNav(
    currentTab: AppRootTab,
    onSelect: (AppRootTab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
        AppRootTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}

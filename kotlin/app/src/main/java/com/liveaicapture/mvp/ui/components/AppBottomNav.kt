package com.liveaicapture.mvp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PersonOutline
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
    Capture("主页", Icons.Outlined.Home),
    Community("\u793e\u533a", Icons.Outlined.People),
    Settings("\u6211\u7684", Icons.Outlined.PersonOutline),
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

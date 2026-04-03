package com.liveaicapture.mvp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    openHome: () -> Unit,
    openCommunity: () -> Unit,
) {
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()

    CamMatePage(
        title = "设置",
        subtitle = "账号管理",
        bottomBar = {
            AppBottomNav(currentTab = AppRootTab.Settings) { tab ->
                when (tab) {
                    AppRootTab.Capture -> openHome()
                    AppRootTab.Community -> openCommunity()
                    AppRootTab.Settings -> Unit
                }
            }
        },
    ) {
        item {
            SectionCard {
                Text("账号")
                Text("${authState.user?.nickname ?: "未登录"}  ${authState.user?.email ?: ""}")
                Button(
                    onClick = { viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("退出登录")
                }
            }
        }
    }
}

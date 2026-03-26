package com.liveaicapture.mvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

private enum class HomeTab(
    val label: String,
    val icon: ImageVector,
) {
    Capture("拍摄功能", Icons.Outlined.Tune),
    Community("用户社区", Icons.Outlined.People),
    Settings("个人设置", Icons.Outlined.Settings),
}

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    openCamera: () -> Unit,
    openCommunity: () -> Unit,
    openSettings: () -> Unit,
) {
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Capture) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)) {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                HomeTab.Capture -> CaptureHomeContent(
                    openCamera = openCamera,
                    openCommunity = openCommunity,
                )

                HomeTab.Community -> CommunityHomeContent(openCommunity = openCommunity)
                HomeTab.Settings -> SettingsHomeContent(
                    nickname = authState.user?.nickname.orEmpty(),
                    email = authState.user?.email.orEmpty(),
                    openSettings = openSettings,
                    logout = { viewModel.logout() },
                )
            }
        }
    }
}

@Composable
private fun CaptureHomeContent(
    openCamera: () -> Unit,
    openCommunity: () -> Unit,
) {
    CamMatePage(
        title = "首页",
        subtitle = "按功能快速开始",
    ) {
        item {
            SectionCard {
                Text("辅助拍摄", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "实时构图建议 + 主体框 + 移动引导，帮助更快找到好机位。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openCamera, modifier = Modifier.fillMaxWidth()) {
                    Text("进入辅助拍摄")
                }
            }
        }

        item {
            SectionCard {
                Text("图片推荐", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "按地点和风景类型筛选社区作品，快速找灵感。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openCommunity, modifier = Modifier.fillMaxWidth()) {
                    Text("进入图片推荐")
                }
            }
        }

        item {
            SectionCard {
                Text("AI 融合", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "上传半身/全身照，选参考图，一键生成创意融合图。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openCommunity, modifier = Modifier.fillMaxWidth()) {
                    Text("进入 AI 融合")
                }
            }
        }
    }
}

@Composable
private fun CommunityHomeContent(
    openCommunity: () -> Unit,
) {
    CamMatePage(
        title = "首页",
        subtitle = "社区内容、推荐和 AI 融合统一从这里进入",
    ) {
        item {
            SectionCard {
                Text("用户社区", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "查看社区流、按地点/类型推荐、选择参考图进行融合。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openCommunity, modifier = Modifier.fillMaxWidth()) {
                    Text("打开用户社区")
                }
            }
        }
    }
}

@Composable
private fun SettingsHomeContent(
    nickname: String,
    email: String,
    openSettings: () -> Unit,
    logout: () -> Unit,
) {
    CamMatePage(
        title = "首页",
        subtitle = "账号和拍摄参数管理",
    ) {
        item {
            SectionCard {
                Text("当前账号", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${nickname.ifBlank { "未登录" }}  ${email.ifBlank { "" }}".trim(),
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开个人设置")
                }
                Button(onClick = logout, modifier = Modifier.fillMaxWidth()) {
                    Text("退出登录")
                }
            }
        }
    }
}

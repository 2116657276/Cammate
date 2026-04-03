package com.liveaicapture.mvp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

@Composable
fun HomeScreen(
    openCamera: () -> Unit,
    openPoseRecommend: () -> Unit,
    openAiCompose: () -> Unit,
    openCommunity: () -> Unit,
    openSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CaptureHomeContent(
            openCamera = openCamera,
            openPoseRecommend = openPoseRecommend,
            openAiCompose = openAiCompose,
            openCommunity = openCommunity,
            openSettings = openSettings,
        )
    }
}

@Composable
private fun CaptureHomeContent(
    openCamera: () -> Unit,
    openPoseRecommend: () -> Unit,
    openAiCompose: () -> Unit,
    openCommunity: () -> Unit,
    openSettings: () -> Unit,
) {
    CamMatePage(
        title = "拍摄",
        subtitle = "辅助拍摄、姿势推荐和 AI 融合",
        bottomBar = {
            AppBottomNav(currentTab = AppRootTab.Capture) { tab ->
                when (tab) {
                    AppRootTab.Capture -> Unit
                    AppRootTab.Community -> openCommunity()
                    AppRootTab.Settings -> openSettings()
                }
            }
        },
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
                Text("姿势推荐", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "从社区选参考图，生成姿态、构图和机位提示，再回到拍摄页照着拍。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(onClick = openPoseRecommend, modifier = Modifier.fillMaxWidth()) {
                    Text("进入姿势推荐")
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
                Button(onClick = openAiCompose, modifier = Modifier.fillMaxWidth()) {
                    Text("进入 AI 融合")
                }
            }
        }
    }
}

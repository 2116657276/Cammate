package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

private data class CommunityPublishSceneOption(
    val raw: String,
    val label: String,
)

private val publishScenes = listOf(
    CommunityPublishSceneOption(raw = "general", label = "通用"),
    CommunityPublishSceneOption(raw = "portrait", label = "人像"),
    CommunityPublishSceneOption(raw = "landscape", label = "风景"),
    CommunityPublishSceneOption(raw = "food", label = "美食"),
    CommunityPublishSceneOption(raw = "night", label = "夜景"),
)

@Composable
fun CommunityPublishScreen(
    viewModel: MainViewModel,
    onBackToCommunity: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    val publishPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateDirectPublishImageUri(uri?.toString())
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    BackHandler(onBack = onBackToCommunity)

    CamMatePage(
        title = "发布动态",
        subtitle = "单独编辑内容，再发布到朋友圈。",
        onBack = onBackToCommunity,
        backText = "返回朋友圈",
    ) {
        item {
            SectionCard {
                Text("发布内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { publishPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.publishImageUri.isNullOrBlank()) "选择相册图片" else "更换图片")
                    }
                    Button(
                        onClick = { viewModel.useLatestPhotoForDirectPublish() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("使用最近拍摄")
                    }
                }
                state.publishImageUri?.takeIf { it.isNotBlank() }?.let { uri ->
                    Text("已选图片：$uri", color = MaterialTheme.colorScheme.secondary)
                }
                OutlinedTextField(
                    value = state.publishPlaceTag,
                    onValueChange = { viewModel.updateDirectPublishPlaceTag(it) },
                    label = { Text("地点标签") },
                    placeholder = { Text("例如：外滩、海边、老街") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    publishScenes.forEach { scene ->
                        FilterChip(
                            selected = state.publishSceneType == scene.raw,
                            onClick = { viewModel.updateDirectPublishSceneType(scene.raw) },
                            label = { Text(scene.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = state.publishCaption,
                    onValueChange = { viewModel.updateDirectPublishCaption(it) },
                    label = { Text("帖子文案（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Button(
                    onClick = { viewModel.publishDirectPost() },
                    enabled = !state.publishingDirect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.publishingDirect) "发布中..." else "发布到社区")
                }
            }
        }
    }
}

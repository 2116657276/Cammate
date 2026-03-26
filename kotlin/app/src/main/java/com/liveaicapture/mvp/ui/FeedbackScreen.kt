package com.liveaicapture.mvp.ui

import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

private data class PublishSceneOption(
    val raw: String,
    val label: String,
)

private val publishSceneOptions = listOf(
    PublishSceneOption(raw = "general", label = "通用"),
    PublishSceneOption(raw = "portrait", label = "人像"),
    PublishSceneOption(raw = "landscape", label = "风景"),
    PublishSceneOption(raw = "food", label = "美食"),
    PublishSceneOption(raw = "night", label = "夜景"),
)

@Composable
fun FeedbackScreen(
    viewModel: MainViewModel,
    finishToCamera: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.feedbackUiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.submitted) {
        if (state.submitted) {
            viewModel.finishFeedbackFlow()
            finishToCamera()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    CamMatePage(
        title = "拍摄反馈",
        subtitle = "你的反馈会帮助 CamMate 持续优化建议",
    ) {
        item {
            SectionCard {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                    update = { imageView ->
                        val uri = state.photoUri
                        if (!uri.isNullOrBlank()) {
                            imageView.setImageURI(Uri.parse(uri))
                        }
                    },
                )
            }
        }

        item {
            SectionCard {
                Text("场景：${state.scene.label} · ${if (state.isRetouched) "已修图" else "原图"}")
                Text("建议：${state.tipText}", style = MaterialTheme.typography.bodyLarge)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    (1..5).forEach { star ->
                        val filled = star <= state.rating
                        Text(
                            text = if (filled) "★" else "☆",
                            fontSize = 28.sp,
                            color = if (filled) Color(0xFFF59E0B) else Color(0xFF94A3B8),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    (1..5).forEach { value ->
                        Button(onClick = { viewModel.updateFeedbackRating(value) }) {
                            Text(value.toString())
                        }
                    }
                }

                OutlinedTextField(
                    value = state.reviewText,
                    onValueChange = { viewModel.updateFeedbackReviewText(it) },
                    label = { Text("文字评价（可选）") },
                    placeholder = { Text("写下本次拍摄体验和建议") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("发布到社区")
                        Text("勾选后会在提交评分后自动发布", color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(
                        checked = state.publishToCommunity,
                        onCheckedChange = { viewModel.updateFeedbackPublishEnabled(it) },
                    )
                }

                if (state.publishToCommunity) {
                    OutlinedTextField(
                        value = state.publishPlaceTag,
                        onValueChange = { viewModel.updateFeedbackPublishPlaceTag(it) },
                        label = { Text("地点标签") },
                        placeholder = { Text("例如：外滩、森林、公园") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        publishSceneOptions.forEach { scene ->
                            FilterChip(
                                selected = state.publishSceneType == scene.raw,
                                onClick = { viewModel.updateFeedbackPublishSceneType(scene.raw) },
                                label = { Text(scene.label) },
                            )
                        }
                    }
                }

                state.errorMessage?.let { Text(it, color = Color(0xFFB42318)) }

                Button(
                    onClick = { viewModel.submitFeedback() },
                    enabled = !state.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.submitting) "提交中..." else "完成评分并提交")
                }
            }
        }
    }
}

package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard
import okhttp3.Headers

private data class ComposeSceneOption(
    val raw: String,
    val label: String,
)

private val composeScenes = listOf(
    ComposeSceneOption(raw = "general", label = "通用"),
    ComposeSceneOption(raw = "portrait", label = "人像"),
    ComposeSceneOption(raw = "landscape", label = "风景"),
    ComposeSceneOption(raw = "food", label = "美食"),
    ComposeSceneOption(raw = "night", label = "夜景"),
)

@Composable
fun AiComposeScreen(
    viewModel: MainViewModel,
    onBackToCapture: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var placeInput by rememberSaveable(state.recommendationPlaceTag) {
        mutableStateOf(state.recommendationPlaceTag)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCommunityPersonImageUri(uri?.toString())
    }

    LaunchedEffect(authState.authenticated) {
        if (!authState.authenticated) return@LaunchedEffect
        viewModel.refreshRecommendations()
        if (state.feed.isEmpty()) {
            viewModel.refreshCommunityFeed(reset = true)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    CamMatePage(
        title = "AI 融合",
        subtitle = "选参考图、上传人物照，直接发起融合任务。",
        onBack = onBackToCapture,
        backText = "返回拍摄分栏",
    ) {
        item {
            SectionCard {
                Text("融合设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "参考图 ID：${state.referencePostId ?: "未选择"}",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.personImageUri.isNullOrBlank()) "选择人物照片" else "更换人物照片")
                }
                state.personImageUri?.takeIf { it.isNotBlank() }?.let {
                    Text("人物图：$it", color = MaterialTheme.colorScheme.secondary)
                }
                Text("融合强度 ${"%.0f".format(state.composeStrength * 100)}%")
                Slider(
                    value = state.composeStrength,
                    onValueChange = { viewModel.updateCommunityComposeStrength(it) },
                    valueRange = 0f..1f,
                )
                Button(
                    onClick = { viewModel.composeCommunityImage() },
                    enabled = !state.composing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.composing) {
                            "融合任务进行中 ${state.composeJobProgress.coerceIn(0, 100)}%"
                        } else {
                            "开始 AI 融合"
                        },
                    )
                }
                if (state.composeJobStatus.isNotBlank()) {
                    Text("任务状态：${state.composeJobStatus} · ${state.composeJobProgress.coerceIn(0, 100)}%")
                    if (state.composeJobStatus == "queued" || state.composeJobStatus == "running") {
                        LinearProgressIndicator(
                            progress = { state.composeJobProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { viewModel.cancelComposeJob() }) {
                            Text("取消融合任务")
                        }
                    }
                    if (state.composeJobStatus == "failed") {
                        state.composeErrorMessage.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color(0xFFE76F51))
                        }
                        TextButton(onClick = { viewModel.retryComposeJob() }) {
                            Text("重试融合任务")
                        }
                    }
                    if (state.composeImplementationStatus == "placeholder") {
                        Text("当前使用本地占位输出，适合演示链路。", color = Color(0xFFF4A261))
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("场景筛选", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = placeInput,
                    onValueChange = {
                        placeInput = it
                        viewModel.updateRecommendationPlaceTag(it)
                    },
                    label = { Text("地点标签") },
                    placeholder = { Text("例如：天台、街角、餐厅") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    composeScenes.forEach { scene ->
                        FilterChip(
                            selected = state.recommendationSceneType == scene.raw,
                            onClick = { viewModel.updateRecommendationSceneType(scene.raw) },
                            label = { Text(scene.label) },
                        )
                    }
                }
                Button(
                    onClick = { viewModel.refreshRecommendations() },
                    enabled = !state.loadingRecommendations,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.loadingRecommendations) "推荐中..." else "刷新参考推荐")
                }
            }
        }

        item {
            SectionCard {
                Text("选择参考图", style = MaterialTheme.typography.titleMedium)
                if (state.loadingRecommendations && state.recommendations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.recommendations.isEmpty() && state.feed.isEmpty()) {
                    Text("还没有可选参考图，请稍后刷新。", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.recommendations.map { it.post }.ifEmpty { state.feed.take(8) }.forEach { post ->
                            ComposeReferenceCard(
                                post = post,
                                authHeader = state.authHeader,
                                selected = state.referencePostId == post.id,
                                onSelect = { viewModel.selectCommunityReferencePost(post.id) },
                            )
                        }
                    }
                }
            }
        }

        state.composedPreviewBase64?.let { after ->
            item {
                SectionCard {
                    Text("融合结果", style = MaterialTheme.typography.titleMedium)
                    val before = state.composeCompareInputBase64
                    if (!before.isNullOrBlank()) {
                        ComposeBeforeAfterSlider(
                            beforeBase64 = before,
                            afterBase64 = after,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        )
                    } else {
                        ComposeBase64Preview(
                            base64Data = after,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveComposedPreviewToGallery { uri ->
                                    Toast.makeText(
                                        context,
                                        if (uri.isNullOrBlank()) "保存失败" else "已保存到相册",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存到相册")
                        }
                        TextButton(
                            onClick = { viewModel.clearComposedPreview() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("清除预览")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeReferenceCard(
    post: CommunityPostItem,
    authHeader: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(post.imageUrl)
        .crossfade(true)
        .headers(
            Headers.Builder().apply {
                if (authHeader.isNotBlank()) {
                    add("Authorization", authHeader)
                }
            }.build(),
        )
        .build()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "compose-reference-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = if (selected) "已选参考 #${post.id}" else "参考 #${post.id}",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${post.placeTag} · ${composeSceneLabel(post.sceneType)} · ${post.likeCount} 赞",
            color = MaterialTheme.colorScheme.secondary,
        )
        Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected) "当前已选" else "设为融合参考")
        }
    }
}

private fun composeSceneLabel(raw: String): String {
    return composeScenes.firstOrNull { it.raw == raw.lowercase() }?.label ?: raw
}

@Composable
private fun ComposeBeforeAfterSlider(
    beforeBase64: String,
    afterBase64: String,
    modifier: Modifier = Modifier,
) {
    var split by rememberSaveable { mutableFloatStateOf(0.5f) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val width = maxWidth
            ComposeBase64Preview(
                base64Data = beforeBase64,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .width(width * split)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(0.dp)),
            ) {
                ComposeBase64Preview(
                    base64Data = afterBase64,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFF4A261))
                    .align(Alignment.CenterStart),
            )
        }
        Slider(
            value = split,
            onValueChange = { split = it.coerceIn(0.1f, 0.9f) },
            valueRange = 0.1f..0.9f,
        )
    }
}

@Composable
private fun ComposeBase64Preview(
    base64Data: String,
    modifier: Modifier = Modifier,
) {
    val decoded = androidx.compose.runtime.remember(base64Data) {
        try {
            android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 120.dp),
        update = { imageView ->
            if (decoded == null) {
                imageView.setImageURI(Uri.EMPTY)
                return@AndroidView
            }
            val bmp = BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            imageView.setImageBitmap(bmp)
        },
    )
}

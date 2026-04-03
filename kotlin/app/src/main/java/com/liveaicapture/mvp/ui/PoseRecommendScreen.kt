package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.data.CommunityRecommendationItem
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard
import okhttp3.Headers

private data class PoseSceneOption(
    val raw: String,
    val label: String,
)

private val poseScenes = listOf(
    PoseSceneOption(raw = "general", label = "通用"),
    PoseSceneOption(raw = "portrait", label = "人像"),
    PoseSceneOption(raw = "landscape", label = "风景"),
    PoseSceneOption(raw = "food", label = "美食"),
    PoseSceneOption(raw = "night", label = "夜景"),
)

@Composable
fun PoseRecommendScreen(
    viewModel: MainViewModel,
    onBackToCapture: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var placeInput by rememberSaveable(state.recommendationPlaceTag) {
        mutableStateOf(state.recommendationPlaceTag)
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
        title = "姿势推荐",
        subtitle = "选参考图，生成姿态与构图提示，再回到拍摄页照着拍。",
        onBack = onBackToCapture,
        backText = "返回拍摄分栏",
    ) {
        item {
            SectionCard {
                Text("当前参考", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "参考图 ID：${state.referencePostId ?: "未选择"}",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val referenceId = state.referencePostId
                            if (referenceId != null && referenceId > 0) {
                                viewModel.requestRemakeGuide(referenceId)
                            } else {
                                Toast.makeText(context, "请先选择一张参考图", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !state.remakeLoading,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.remakeLoading) "生成中..." else "生成姿势提示")
                    }
                    Button(
                        onClick = { viewModel.analyzeLatestPhotoForRemake() },
                        enabled = !state.remakeAnalyzing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.remakeAnalyzing) "分析中..." else "分析最近拍摄")
                    }
                }
                state.remakeGuide?.let { guide ->
                    Text("机位：${guide.cameraHint}", color = MaterialTheme.colorScheme.primary)
                    guide.poseHint.takeIf { it.isNotBlank() }?.let {
                        Text("姿态：$it", color = MaterialTheme.colorScheme.primary)
                    }
                    guide.framingHint.takeIf { it.isNotBlank() }?.let {
                        Text("构图：$it", color = MaterialTheme.colorScheme.primary)
                    }
                    guide.timingHint.takeIf { it.isNotBlank() }?.let {
                        Text("时机：$it", color = MaterialTheme.colorScheme.primary)
                    }
                    guide.shotScript.forEachIndexed { index, line ->
                        Text("${index + 1}. $line")
                    }
                    Button(
                        onClick = {
                            viewModel.applyRemakeGuideToCamera()
                            onBackToCapture()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("应用到拍摄页")
                    }
                }
                state.remakeAnalysis?.let { analysis ->
                    Text(
                        text = "姿势 ${"%.0f".format(analysis.poseScore * 100)}% · 构图 ${"%.0f".format(analysis.framingScore * 100)}% · 综合 ${"%.0f".format(analysis.alignmentScore * 100)}%",
                        color = MaterialTheme.colorScheme.primary,
                    )
                    analysis.mismatchHints.forEach { hint ->
                        Text("- $hint")
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("场景筛选", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = placeInput,
                    onValueChange = {
                        placeInput = it
                        viewModel.updateRecommendationPlaceTag(it)
                    },
                    label = { Text("地点标签") },
                    placeholder = { Text("例如：海边、街角、咖啡馆") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    poseScenes.forEach { scene ->
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
                    Text(if (state.loadingRecommendations) "推荐中..." else "刷新场景推荐")
                }
            }
        }

        item {
            SectionCard {
                Text("推荐参考", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.loadingRecommendations && state.recommendations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.recommendations.isEmpty()) {
                    Text("还没有命中推荐，先试试调整地点和分类。", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.recommendations.forEach { item ->
                            PoseReferenceCard(
                                post = item.post,
                                authHeader = state.authHeader,
                                reason = item.reason,
                                selected = state.referencePostId == item.post.id,
                                onSelect = { viewModel.selectCommunityReferencePost(item.post.id) },
                                onGenerate = {
                                    viewModel.selectCommunityReferencePost(item.post.id)
                                    viewModel.requestRemakeGuide(item.post.id)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.feed.isNotEmpty()) {
            item {
                SectionCard {
                    Text("最近朋友圈参考", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.feed.take(6).forEach { post ->
                            PoseReferenceCard(
                                post = post,
                                authHeader = state.authHeader,
                                reason = null,
                                selected = state.referencePostId == post.id,
                                onSelect = { viewModel.selectCommunityReferencePost(post.id) },
                                onGenerate = {
                                    viewModel.selectCommunityReferencePost(post.id)
                                    viewModel.requestRemakeGuide(post.id)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PoseReferenceCard(
    post: CommunityPostItem,
    authHeader: String,
    reason: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onGenerate: () -> Unit,
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
            contentDescription = "pose-reference-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = if (selected) "已选参考 #${post.id}" else "参考 #${post.id}",
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${post.placeTag} · ${poseSceneLabel(post.sceneType)} · ${post.likeCount} 赞",
            color = MaterialTheme.colorScheme.secondary,
        )
        reason?.takeIf { it.isNotBlank() }?.let {
            Text("推荐理由：$it", color = MaterialTheme.colorScheme.primary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
                Text(if (selected) "当前已选" else "设为参考")
            }
            Button(onClick = onGenerate, modifier = Modifier.weight(1f)) {
                Text("生成姿势提示")
            }
        }
    }
}

private fun poseSceneLabel(raw: String): String {
    return poseScenes.firstOrNull { it.raw == raw.lowercase() }?.label ?: raw
}

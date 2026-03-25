package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard
import androidx.compose.foundation.rememberScrollState
import okhttp3.Headers

private val communityScenes = listOf("general", "portrait", "landscape", "food", "night")

@Composable
fun CommunityScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var placeInput by rememberSaveable { mutableStateOf(state.recommendationPlaceTag) }
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCommunityPersonImageUri(uri?.toString())
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCommunityFeed(reset = true)
        viewModel.refreshRecommendations()
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    CamMatePage(
        title = "创作者社区",
        subtitle = "发现真实作品、获取灵感，并一键生成你的创意照片",
        onBack = onBack,
    ) {
        item {
            SectionCard {
                Text("推荐筛选", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = placeInput,
                    onValueChange = {
                        placeInput = it
                        viewModel.updateRecommendationPlaceTag(it)
                    },
                    label = { Text("地点标签") },
                    placeholder = { Text("例如：外滩、迪士尼、海边") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    communityScenes.forEach { scene ->
                        FilterChip(
                            selected = state.recommendationSceneType == scene,
                            onClick = { viewModel.updateRecommendationSceneType(scene) },
                            label = { Text(scene) },
                        )
                    }
                }
                Button(
                    onClick = { viewModel.refreshRecommendations() },
                    enabled = !state.loadingRecommendations,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loadingRecommendations) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .height(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    }
                    Text("刷新推荐")
                }
            }
        }

        item {
            SectionCard {
                Text("推荐结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (state.recommendations.isEmpty()) {
                    Text("暂无推荐，试试调整地点和类型筛选。", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.recommendations.forEach { recommendation ->
                            PostCard(
                                post = recommendation.post,
                                reason = recommendation.reason,
                                authHeader = state.authHeader,
                                onUseAsReference = { viewModel.selectCommunityReferencePost(recommendation.post.id) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("AI 融合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "选择一张社区参考图，再上传你的半身或全身照，生成创意融合图。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("已选参考图 ID：${state.referencePostId ?: "未选择"}")
                Button(
                    onClick = { pickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.personImageUri.isNullOrBlank()) "选择人物照片" else "更换人物照片")
                }
                state.personImageUri?.let {
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
                    Text(if (state.composing) "生成中..." else "开始 AI 融合")
                }
                if (!state.composeRequestId.isNullOrBlank()) {
                    Text("请求ID：${state.composeRequestId}", color = MaterialTheme.colorScheme.secondary)
                }
                state.composedPreviewBase64?.let { preview ->
                    Base64Preview(
                        base64Data = preview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                    )
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

        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("社区流", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { viewModel.refreshCommunityFeed(reset = true) }) {
                        Text("刷新")
                    }
                }
                if (state.loadingFeed) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.feed.isEmpty()) {
                    Text("社区暂时还没有内容，发布你的作品吧。", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.feed.forEach { post ->
                            PostCard(
                                post = post,
                                authHeader = state.authHeader,
                                onUseAsReference = { viewModel.selectCommunityReferencePost(post.id) },
                            )
                        }
                        TextButton(
                            onClick = { viewModel.refreshCommunityFeed(reset = false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("加载更多")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostCard(
    post: CommunityPostItem,
    reason: String? = null,
    authHeader: String,
    onUseAsReference: () -> Unit,
) {
    val context = LocalContext.current
    val request = remember(post.imageUrl, authHeader) {
        ImageRequest.Builder(context)
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
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x332A3342), RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "community-post-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clickable(onClick = onUseAsReference),
            contentScale = ContentScale.Crop,
        )
        Text("作者：${post.userNickname} · ${post.placeTag} · ${post.sceneType}")
        Text("评分：${post.rating}/5")
        if (post.reviewText.isNotBlank()) {
            Text(post.reviewText, color = MaterialTheme.colorScheme.secondary)
        }
        reason?.takeIf { it.isNotBlank() }?.let {
            Text("推荐理由：$it", color = Color(0xFFF4A261))
        }
        TextButton(onClick = onUseAsReference) {
            Text("设为融合参考图")
        }
    }
}

@Composable
private fun Base64Preview(
    base64Data: String,
    modifier: Modifier = Modifier,
) {
    val decoded = remember(base64Data) {
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
        modifier = modifier,
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

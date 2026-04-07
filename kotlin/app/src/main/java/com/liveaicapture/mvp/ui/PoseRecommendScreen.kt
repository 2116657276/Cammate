package com.liveaicapture.mvp.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.data.CommunityRemakeGuide
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
    PoseSceneOption(raw = "pet", label = "宠物"),
    PoseSceneOption(raw = "flower", label = "花草"),
)

@Composable
fun PoseRecommendScreen(
    viewModel: MainViewModel,
    onBackToCapture: () -> Unit,
    openPostDetail: (Int) -> Unit,
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

    val recommendationPosts = state.recommendations.map { it.post }.ifEmpty { state.feed.take(12) }

    CamMatePage(
        title = "拍摄推荐",
        onBack = onBackToCapture,
        backText = "返回",
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = placeInput,
                    onValueChange = {
                        placeInput = it
                        viewModel.updateRecommendationPlaceTag(it)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("按场景标签筛选") },
                )
                Button(
                    onClick = { viewModel.refreshRecommendations() },
                    enabled = !state.loadingRecommendations,
                ) {
                    Text("筛选")
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                poseScenes.forEach { scene ->
                    FilterChip(
                        selected = state.recommendationSceneType == scene.raw,
                        onClick = {
                            viewModel.updateRecommendationSceneType(scene.raw)
                            viewModel.refreshRecommendations()
                        },
                        label = { Text(scene.label) },
                    )
                }
            }
        }

        item {
            Text(
                text = "精选社区推荐：",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        item {
            when {
                state.loadingRecommendations && recommendationPosts.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                recommendationPosts.isEmpty() -> {
                    EmptyRecommendationState()
                }

                else -> {
                    RecommendationGallery(
                        posts = recommendationPosts,
                        authHeader = state.authHeader,
                        openPostDetail = openPostDetail,
                    )
                }
            }
        }
    }
}

@Composable
fun PoseReferenceDetailScreen(
    viewModel: MainViewModel,
    postId: Int,
    onBack: () -> Unit,
) {
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    val post = state.feed.firstOrNull { it.id == postId }
        ?: state.recommendations.firstOrNull { it.post.id == postId }?.post
    val guide = state.remakeGuide?.takeIf { it.templatePost.id == postId }

    LaunchedEffect(postId) {
        viewModel.selectCommunityReferencePost(postId)
        viewModel.requestRemakeGuide(postId)
    }

    CamMatePage(
        title = "",
        showHeader = false,
        horizontalPadding = 16.dp,
        onBack = null,
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onBack) {
                    Text("返回")
                }
            }
        }

        item {
            if (post == null) {
                SectionCard {
                    Text(
                        text = "未找到图片详情",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                PoseDetailCard(
                    post = post,
                    authHeader = state.authHeader,
                    guide = guide,
                )
            }
        }
    }
}

@Composable
private fun RecommendationGallery(
    posts: List<CommunityPostItem>,
    authHeader: String,
    openPostDetail: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        posts.forEach { post ->
            PoseGalleryCard(
                post = post,
                authHeader = authHeader,
                onClick = { openPostDetail(post.id) },
            )
        }
    }
}

@Composable
private fun EmptyRecommendationState() {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无推荐图片，先换个场景标签试试",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PoseGalleryCard(
    post: CommunityPostItem,
    authHeader: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val request = rememberPosePostRequest(
        context = context,
        imageUrl = post.imageUrl,
        authHeader = authHeader,
    )

    Column(
        modifier = Modifier
            .width(312.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AsyncImage(
            model = request,
            contentDescription = "pose-gallery-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.08f)
                .clip(RoundedCornerShape(30.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentScale = ContentScale.Crop,
        )
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.76f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = post.placeTag.ifBlank { "社区精选图片" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${poseSceneLabel(post.sceneType)} · ${post.likeCount}赞 · 点击查看详情",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PoseDetailCard(
    post: CommunityPostItem,
    authHeader: String,
    guide: CommunityRemakeGuide?,
) {
    val context = LocalContext.current
    val request = rememberPosePostRequest(
        context = context,
        imageUrl = post.imageUrl,
        authHeader = authHeader,
    )

    SectionCard {
        AsyncImage(
            model = request,
            contentDescription = "pose-detail-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp)),
            contentScale = ContentScale.Crop,
        )
        PoseDetailItem("场景", poseSceneLabel(post.sceneType))
        PoseDetailItem("地点标签", post.placeTag.ifBlank { "未填写" })
        if (post.caption.isNotBlank()) {
            PoseDetailItem("图片说明", post.caption)
        }
        PoseDetailItem(
            "角度机位",
            poseCameraGuideText(post = post, guide = guide),
        )
        guide?.poseHint?.takeIf { it.isNotBlank() }?.let {
            PoseDetailItem("动作姿势", it)
        }
        guide?.timingHint?.takeIf { it.isNotBlank() }?.let {
            PoseDetailItem("拍摄时机", it)
        }
        if (post.reviewText.isNotBlank()) {
            PoseDetailItem("详细推荐", post.reviewText)
        }
        guide?.alignmentChecks
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                PoseDetailItem("对齐检查", it.joinToString("\n"))
            }
    }
}

private fun poseCameraGuideText(
    post: CommunityPostItem,
    guide: CommunityRemakeGuide?,
): String {
    val parts = buildList {
        guide?.cameraHint?.takeIf { it.isNotBlank() }?.let { add(it) }
        guide?.framingHint?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val fallbackParts = if (parts.isEmpty() && post.reviewText.isNotBlank()) {
        listOf(post.reviewText)
    } else {
        parts
    }
    return fallbackParts.joinToString("\n").ifBlank { "暂未生成角度机位建议" }
}

@Composable
private fun PoseDetailItem(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun rememberPosePostRequest(
    context: Context,
    imageUrl: String,
    authHeader: String,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(imageUrl)
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

private fun poseSceneLabel(raw: String): String {
    return poseScenes.firstOrNull { it.raw == raw.lowercase() }?.label ?: raw
}

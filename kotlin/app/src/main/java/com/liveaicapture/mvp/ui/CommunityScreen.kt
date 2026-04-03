package com.liveaicapture.mvp.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityCommentItem
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard
import okhttp3.Headers

private data class CommunitySceneOption(
    val raw: String,
    val label: String,
)

private val communityScenes = listOf(
    CommunitySceneOption(raw = "general", label = "通用"),
    CommunitySceneOption(raw = "portrait", label = "人像"),
    CommunitySceneOption(raw = "landscape", label = "风景"),
    CommunitySceneOption(raw = "food", label = "美食"),
    CommunitySceneOption(raw = "night", label = "夜景"),
)

@Composable
fun CommunityScreen(
    viewModel: MainViewModel,
    openCapture: () -> Unit,
    openSettings: () -> Unit,
    openPublish: () -> Unit,
    openTools: () -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()

    LaunchedEffect(authState.authenticated) {
        if (!authState.authenticated) return@LaunchedEffect
        viewModel.refreshCommunityFeed(reset = true)
        viewModel.refreshRecommendations()
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }
    BackHandler(onBack = openCapture)

    CamMatePage(
        title = "用户社区",
        subtitle = "像朋友圈一样发布、浏览与互动",
        bottomBar = {
            AppBottomNav(currentTab = AppRootTab.Community) { tab ->
                when (tab) {
                    AppRootTab.Capture -> openCapture()
                    AppRootTab.Community -> Unit
                    AppRootTab.Settings -> openSettings()
                }
            }
        },
    ) {
        item {
            SectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("朋友圈动态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = openPublish) {
                            Text("发布动态")
                        }
                        TextButton(onClick = openTools) {
                            Text("玩法入口")
                        }
                    }
                }
                Text("打开社区先看朋友圈，玩法功能在右上角入口展开。", color = MaterialTheme.colorScheme.secondary)
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
                    Text("社区暂时还没有内容，发布你的第一条动态吧。", color = MaterialTheme.colorScheme.secondary)
                    TextButton(onClick = { viewModel.refreshCommunityFeed(reset = true) }) {
                        Text("重新刷新动态")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.feed.forEach { post ->
                            PostCard(
                                post = post,
                                authHeader = state.authHeader,
                                comments = state.commentsByPost[post.id].orEmpty(),
                                commentDraft = state.commentDraftByPost[post.id].orEmpty(),
                                commentsLoading = state.loadingCommentsPostId == post.id,
                                onToggleLike = { viewModel.togglePostLike(post) },
                                onLoadComments = { viewModel.loadComments(post.id) },
                                onCommentDraftChange = { viewModel.updateCommentDraft(post.id, it) },
                                onSubmitComment = { viewModel.submitComment(post.id) },
                                onDeleteComment = { commentId -> viewModel.deleteComment(post.id, commentId) },
                                onUseAsReference = { viewModel.selectCommunityReferencePost(post.id) },
                                onStartRelay = { viewModel.startRelayFromPost(post.id) },
                                onUseAsTemplate = { viewModel.usePostAsTemplate(post.id) },
                                onRequestRemake = { viewModel.requestRemakeGuide(post.id) },
                            )
                        }
                        TextButton(
                            onClick = { viewModel.refreshCommunityFeed(reset = false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    state.loadingFeed -> "加载中..."
                                    state.feedHasMore -> "加载更多"
                                    else -> "没有更多了"
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
fun PostCard(
    post: CommunityPostItem,
    authHeader: String,
    comments: List<CommunityCommentItem>,
    commentDraft: String,
    commentsLoading: Boolean,
    onToggleLike: () -> Unit,
    onLoadComments: () -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onDeleteComment: (Int) -> Unit,
    onUseAsReference: () -> Unit,
    onStartRelay: () -> Unit,
    onUseAsTemplate: () -> Unit,
    onRequestRemake: () -> Unit,
    reason: String? = null,
) {
    val context = LocalContext.current
    var expanded by rememberSaveable(post.id) { mutableStateOf(false) }
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
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = post.userNickname.take(1).ifBlank { "友" },
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.userNickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${post.placeTag} · ${sceneLabel(post.sceneType)} · ${formatFeedTime(post.createdAt)}",
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (post.postType == "relay") {
                Text(
                    text = "接力",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        AsyncImage(
            model = request,
            contentDescription = "community-post-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clickable(onClick = onUseAsReference),
            contentScale = ContentScale.Crop,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleLike, modifier = Modifier.weight(1f)) {
                Text(if (post.likedByMe) "已赞 ${post.likeCount}" else "点赞 ${post.likeCount}")
            }
            TextButton(
                onClick = {
                    expanded = !expanded
                    if (expanded && comments.isEmpty()) {
                        onLoadComments()
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(if (expanded) "收起详情" else "查看详情")
            }
        }

        if (expanded) {
            if (post.caption.isNotBlank()) {
                Text(post.caption, style = MaterialTheme.typography.bodyLarge)
            }
            if (post.rating > 0) {
                Text("评分 ${post.rating}/5", color = MaterialTheme.colorScheme.secondary)
            }
            if (post.reviewText.isNotBlank()) {
                Text(post.reviewText, color = MaterialTheme.colorScheme.secondary)
            }
            post.relayParentSummary?.let { relay ->
                Text(
                    text = "接力来源：#${relay.id} @${relay.userNickname} · ${relay.placeTag} · ${sceneLabel(relay.sceneType)}",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            reason?.takeIf { it.isNotBlank() }?.let {
                Text("推荐理由：$it", color = MaterialTheme.colorScheme.primary)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onLoadComments, modifier = Modifier.weight(1f)) {
                    Text(if (commentsLoading) "评论加载中" else "评论 ${post.commentCount}")
                }
                TextButton(onClick = onStartRelay, modifier = Modifier.weight(1f)) {
                    Text("接力")
                }
                TextButton(onClick = onRequestRemake, modifier = Modifier.weight(1f)) {
                    Text("同款")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onUseAsReference, modifier = Modifier.weight(1f)) { Text("设为参考") }
                TextButton(onClick = onUseAsTemplate, modifier = Modifier.weight(1f)) { Text("设为模板") }
            }
            OutlinedTextField(
                value = commentDraft,
                onValueChange = onCommentDraftChange,
                label = { Text("评论一下") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Button(onClick = onSubmitComment, modifier = Modifier.fillMaxWidth()) {
                Text("发送评论")
            }
            if (comments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    comments.forEach { comment ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("@${comment.userNickname}", color = MaterialTheme.colorScheme.primary)
                                Text(comment.text)
                            }
                            if (comment.canDelete) {
                                TextButton(onClick = { onDeleteComment(comment.id) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sceneLabel(raw: String): String {
    return communityScenes.firstOrNull { it.raw == raw.lowercase() }?.label ?: raw
}

private fun formatFeedTime(epochSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val diff = (now - epochSec).coerceAtLeast(0L)
    return when {
        diff < 60L -> "刚刚"
        diff < 3600L -> "${diff / 60L} 分钟前"
        diff < 86400L -> "${diff / 3600L} 小时前"
        diff < 86400L * 7L -> "${diff / 86400L} 天前"
        else -> "${diff / 86400L} 天前"
    }
}

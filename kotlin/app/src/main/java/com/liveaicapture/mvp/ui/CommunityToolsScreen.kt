package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.SectionCard

private data class ToolSceneOption(
    val raw: String,
    val label: String,
)

private val toolScenes = listOf(
    ToolSceneOption(raw = "general", label = "通用"),
    ToolSceneOption(raw = "portrait", label = "人像"),
    ToolSceneOption(raw = "landscape", label = "风景"),
    ToolSceneOption(raw = "food", label = "美食"),
    ToolSceneOption(raw = "night", label = "夜景"),
)

@Composable
fun CommunityToolsScreen(
    viewModel: MainViewModel,
    onBackToCommunity: () -> Unit,
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
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    BackHandler(onBack = onBackToCommunity)

    CamMatePage(
        title = "玩法入口",
        subtitle = "场景推荐和姿势模仿都放在这里。",
        onBack = onBackToCommunity,
        backText = "返回朋友圈",
    ) {
        item {
            SectionCard {
                Text("姿势模仿", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "先在朋友圈里点开帖子详情并设为参考，再来这里生成姿势提示。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("当前参考图 ID：${state.referencePostId ?: "未选择"}")
                Button(
                    onClick = {
                        val ref = state.referencePostId
                        if (ref != null && ref > 0) {
                            viewModel.requestRemakeGuide(ref)
                        } else {
                            Toast.makeText(context, "请先在朋友圈选择参考图", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !state.remakeLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.remakeLoading) "生成中..." else "生成姿势提示")
                }
                state.remakeGuide?.let { guide ->
                    Text("机位：${guide.cameraHint}", color = MaterialTheme.colorScheme.primary)
                    guide.poseHint.takeIf { it.isNotBlank() }?.let { Text("姿态：$it") }
                    guide.framingHint.takeIf { it.isNotBlank() }?.let { Text("构图：$it") }
                    Button(
                        onClick = { viewModel.applyRemakeGuideToCamera() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("应用到拍摄页")
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("场景推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    toolScenes.forEach { scene ->
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
                if (state.recommendations.isEmpty()) {
                    Text("暂无推荐，试试调整地点和类型筛选。", color = MaterialTheme.colorScheme.secondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.recommendations.forEach { recommendation ->
                            PostCard(
                                post = recommendation.post,
                                reason = recommendation.reason,
                                authHeader = state.authHeader,
                                comments = state.commentsByPost[recommendation.post.id].orEmpty(),
                                commentDraft = state.commentDraftByPost[recommendation.post.id].orEmpty(),
                                commentsLoading = state.loadingCommentsPostId == recommendation.post.id,
                                onToggleLike = { viewModel.togglePostLike(recommendation.post) },
                                onLoadComments = { viewModel.loadComments(recommendation.post.id) },
                                onCommentDraftChange = { viewModel.updateCommentDraft(recommendation.post.id, it) },
                                onSubmitComment = { viewModel.submitComment(recommendation.post.id) },
                                onDeleteComment = { commentId ->
                                    viewModel.deleteComment(recommendation.post.id, commentId)
                                },
                                onUseAsReference = { viewModel.selectCommunityReferencePost(recommendation.post.id) },
                                onStartRelay = { viewModel.startRelayFromPost(recommendation.post.id) },
                                onUseAsTemplate = { viewModel.usePostAsTemplate(recommendation.post.id) },
                                onRequestRemake = { viewModel.requestRemakeGuide(recommendation.post.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

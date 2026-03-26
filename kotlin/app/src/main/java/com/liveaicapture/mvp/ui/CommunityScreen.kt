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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityCommentItem
import com.liveaicapture.mvp.data.CommunityPostItem
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
    onBack: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var showToolPanel by rememberSaveable { mutableStateOf(false) }
    var placeInput by rememberSaveable { mutableStateOf(state.recommendationPlaceTag) }
    val publishPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateDirectPublishImageUri(uri?.toString())
    }
    val composePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCommunityPersonImageUri(uri?.toString())
    }
    val cocreateAPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCocreatePersonAUri(uri?.toString())
    }
    val cocreateBPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        viewModel.updateCocreatePersonBUri(uri?.toString())
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
    // 显式接管系统返回键，避免部分虚拟机上仅依赖导航栈导致返回无响应。
    BackHandler(onBack = onBack)

    CamMatePage(
        title = "用户社区",
        subtitle = "像朋友圈一样发布、浏览与互动",
        onBack = onBack,
        backText = "返回首页",
        topActionText = "退出登录",
        onTopAction = onLogout,
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
                        TextButton(onClick = { showToolPanel = !showToolPanel }) {
                            Text(if (showToolPanel) "收起玩法" else "玩法入口")
                        }
                        TextButton(onClick = { viewModel.refreshCommunityFeed(reset = true) }) {
                            Text("刷新")
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
                            Text("加载更多")
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("发布动态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "支持相册选择与最近拍摄，一键分享你的拍摄瞬间。",
                    color = MaterialTheme.colorScheme.secondary,
                )
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
                    Text("已选：$uri", color = MaterialTheme.colorScheme.secondary)
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
                    communityScenes.forEach { scene ->
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
                OutlinedTextField(
                    value = state.publishReviewText,
                    onValueChange = { viewModel.updateDirectPublishReviewText(it) },
                    label = { Text("文字评价（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Text("评分（可选）", color = MaterialTheme.colorScheme.secondary)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.publishRating == null,
                        onClick = { viewModel.updateDirectPublishRating(null) },
                        label = { Text("不评分") },
                    )
                    (1..5).forEach { rating ->
                        FilterChip(
                            selected = state.publishRating == rating,
                            onClick = { viewModel.updateDirectPublishRating(rating) },
                            label = { Text("$rating 星") },
                        )
                    }
                }
                if (state.publishPostType == "relay" && (state.publishRelayParentPostId ?: 0) > 0) {
                    Text("接力来源帖子：#${state.publishRelayParentPostId}", color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { viewModel.resetDirectPublishToNormal() }) {
                        Text("取消接力模式")
                    }
                }
                Button(
                    onClick = { viewModel.publishDirectPost() },
                    enabled = !state.publishingDirect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.publishingDirect) "发布中..." else "发布到社区")
                }
            }
        }

        if (showToolPanel) {
            item {
                SectionCard {
                    Text("同款复刻", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "先从朋友圈选择参考图，再生成机位/构图/光线指导卡。",
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text("当前模板 ID：${state.referencePostId ?: "未选择"}")
                    Button(
                        onClick = {
                            val ref = state.referencePostId
                            if (ref != null && ref > 0) {
                                viewModel.requestRemakeGuide(ref)
                            } else {
                                Toast.makeText(context, "请先在朋友圈选择一张参考图", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !state.remakeLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.remakeLoading) "生成中..." else "生成同款复刻指南")
                    }
                    state.remakeGuide?.let { guide ->
                        Text(
                            text = "相机建议：${guide.cameraHint}",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        guide.shotScript.forEachIndexed { index, line ->
                            Text("${index + 1}. $line")
                        }
                        if (guide.placeholderNotes.isNotEmpty()) {
                            guide.placeholderNotes.forEach { note ->
                                Text(note, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        Button(
                            onClick = { viewModel.applyRemakeGuideToCamera() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("应用到拍摄模式")
                        }
                    }
                }
            }

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

        item {
            SectionCard {
                Text("AI 融合", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "选择社区参考图，上传半身或全身照，一键创意融合。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text("参考图 ID：${state.referencePostId ?: "未选择"}")
                Button(
                    onClick = { composePicker.launch("image/*") },
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
                    if (state.composeMaxRetries > 0) {
                        Text(
                            "自动重试：${state.composeRetryCount.coerceAtLeast(0)}/${state.composeMaxRetries.coerceAtLeast(0)}",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    state.composeNextRetryAt?.let { nextRetryAt ->
                        val remain = (nextRetryAt - (System.currentTimeMillis() / 1000L)).coerceAtLeast(0L)
                        if (remain > 0) {
                            Text("下次重试约 ${remain}s 后", color = MaterialTheme.colorScheme.secondary)
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
                    if (state.composeJobStatus == "canceled") {
                        Text("任务已取消", color = MaterialTheme.colorScheme.secondary)
                    }
                    if (state.composeImplementationStatus == "placeholder") {
                        Text("当前为占位输出（placeholder）", color = Color(0xFFF4A261))
                    }
                    state.composePlaceholderNotes.forEach { note ->
                        Text(note, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (!state.composeRequestId.isNullOrBlank()) {
                    Text("请求ID：${state.composeRequestId}", color = MaterialTheme.colorScheme.secondary)
                }
                state.composedPreviewBase64?.let { after ->
                    val before = state.composeCompareInputBase64
                    if (!before.isNullOrBlank()) {
                        BeforeAfterSlider(
                            beforeBase64 = before,
                            afterBase64 = after,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        )
                    } else {
                        Base64Preview(
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

        item {
            SectionCard {
                Text("双人共创", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "上传两张人物照，结合社区参考图生成双人创意合影。",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { cocreateAPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.cocreatePersonAUri.isNullOrBlank()) "选择人物 A" else "更换人物 A")
                    }
                    Button(
                        onClick = { cocreateBPicker.launch("image/*") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (state.cocreatePersonBUri.isNullOrBlank()) "选择人物 B" else "更换人物 B")
                    }
                }
                state.cocreatePersonAUri?.let { Text("人物 A：$it", color = MaterialTheme.colorScheme.secondary) }
                state.cocreatePersonBUri?.let { Text("人物 B：$it", color = MaterialTheme.colorScheme.secondary) }
                Button(
                    onClick = { viewModel.composeCocreateImage() },
                    enabled = !state.cocreating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.cocreating) {
                            "共创任务进行中 ${state.cocreateJobProgress.coerceIn(0, 100)}%"
                        } else {
                            "开始双人共创"
                        },
                    )
                }
                if (state.cocreateJobStatus.isNotBlank()) {
                    Text("任务状态：${state.cocreateJobStatus} · ${state.cocreateJobProgress.coerceIn(0, 100)}%")
                    if (state.cocreateJobStatus == "queued" || state.cocreateJobStatus == "running") {
                        LinearProgressIndicator(
                            progress = { state.cocreateJobProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { viewModel.cancelCocreateJob() }) {
                            Text("取消共创任务")
                        }
                    }
                    if (state.cocreateMaxRetries > 0) {
                        Text(
                            "自动重试：${state.cocreateRetryCount.coerceAtLeast(0)}/${state.cocreateMaxRetries.coerceAtLeast(0)}",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    state.cocreateNextRetryAt?.let { nextRetryAt ->
                        val remain = (nextRetryAt - (System.currentTimeMillis() / 1000L)).coerceAtLeast(0L)
                        if (remain > 0) {
                            Text("下次重试约 ${remain}s 后", color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    if (state.cocreateJobStatus == "failed") {
                        state.cocreateErrorMessage.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = Color(0xFFE76F51))
                        }
                        TextButton(onClick = { viewModel.retryCocreateJob() }) {
                            Text("重试共创任务")
                        }
                    }
                    if (state.cocreateJobStatus == "canceled") {
                        Text("任务已取消", color = MaterialTheme.colorScheme.secondary)
                    }
                    if (state.cocreateImplementationStatus == "placeholder") {
                        Text("当前为占位输出（placeholder）", color = Color(0xFFF4A261))
                    }
                    state.cocreatePlaceholderNotes.forEach { note ->
                        Text(note, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (!state.cocreateRequestId.isNullOrBlank()) {
                    Text("请求ID：${state.cocreateRequestId}", color = MaterialTheme.colorScheme.secondary)
                }
                state.cocreatePreviewBase64?.let { after ->
                    val before = state.cocreateCompareInputBase64
                    if (!before.isNullOrBlank()) {
                        BeforeAfterSlider(
                            beforeBase64 = before,
                            afterBase64 = after,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                        )
                    } else {
                        Base64Preview(
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
                                viewModel.saveCocreatePreviewToGallery { uri ->
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
                            onClick = { viewModel.clearCocreatePreview() },
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
}

@Composable
private fun PostCard(
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
            TextButton(onClick = onToggleLike, modifier = Modifier.weight(1f)) {
                Text(if (post.likedByMe) "已赞 ${post.likeCount}" else "点赞 ${post.likeCount}")
            }
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

@Composable
private fun BeforeAfterSlider(
    beforeBase64: String,
    afterBase64: String,
    modifier: Modifier = Modifier,
) {
    var split by remember { mutableFloatStateOf(0.5f) }
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
            Base64Preview(
                base64Data = beforeBase64,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .width(width * split)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(0.dp)),
            ) {
                Base64Preview(
                    base64Data = afterBase64,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFF4A261))
                    .align(Alignment.CenterStart)
                    .offset(x = (width * split) - 1.dp),
            )
        }
        Slider(
            value = split,
            onValueChange = { split = it.coerceIn(0.1f, 0.9f) },
            valueRange = 0.1f..0.9f,
        )
        Text(
            text = "拖动滑杆查看前后对比",
            color = MaterialTheme.colorScheme.secondary,
        )
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

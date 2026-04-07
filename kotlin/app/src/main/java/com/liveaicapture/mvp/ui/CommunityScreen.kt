package com.liveaicapture.mvp.ui

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.data.CommunityCommentItem
import com.liveaicapture.mvp.data.CommunityPostItem
import com.liveaicapture.mvp.ui.components.AppBottomNav
import com.liveaicapture.mvp.ui.components.AppRootTab
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.PublishEntrySheet
import com.liveaicapture.mvp.ui.components.SectionCard
import okhttp3.Headers

private data class CommunityPhotoTypeOption(
    val raw: String,
    val label: String,
    val aliases: List<String> = emptyList(),
)

private val communityPhotoTypes = listOf(
    CommunityPhotoTypeOption(raw = "all", label = "\u5168\u90e8"),
    CommunityPhotoTypeOption(
        raw = "general",
        label = "\u901a\u7528",
        aliases = listOf("\u901a\u7528"),
    ),
    CommunityPhotoTypeOption(
        raw = "portrait",
        label = "\u4eba\u50cf",
        aliases = listOf("\u4eba\u50cf", "\u4eba\u7269", "\u4eba\u50cf\u7167", "\u62cd\u4eba"),
    ),
    CommunityPhotoTypeOption(
        raw = "landscape",
        label = "\u98ce\u666f",
        aliases = listOf("\u98ce\u666f", "\u666f\u8272", "\u5929\u7a7a", "\u6d77\u8fb9", "\u6e56", "\u4e91"),
    ),
    CommunityPhotoTypeOption(
        raw = "food",
        label = "\u7f8e\u98df",
        aliases = listOf("\u7f8e\u98df", "\u751c\u54c1", "\u86cb\u7cd5", "\u7cd6\u6c34", "\u5403\u7684"),
    ),
    CommunityPhotoTypeOption(
        raw = "pet",
        label = "\u5ba0\u7269",
        aliases = listOf("\u5ba0\u7269", "\u732b", "\u72d7", "\u5c0f\u732b", "\u5c0f\u72d7"),
    ),
    CommunityPhotoTypeOption(
        raw = "flower",
        label = "\u82b1\u8349",
        aliases = listOf("\u82b1", "\u82b1\u5349", "\u6a31\u82b1", "\u82b1\u5899"),
    ),
    CommunityPhotoTypeOption(
        raw = "night",
        label = "\u591c\u666f",
        aliases = listOf("\u591c\u666f", "\u84dd\u8c03", "\u65e5\u843d", "\u665a\u971e", "\u5165\u591c"),
    ),
)

@Composable
fun CommunityScreen(
    viewModel: MainViewModel,
    openCapture: () -> Unit,
    openCamera: () -> Unit,
    openSettings: () -> Unit,
    openPublish: () -> Unit,
    openPostDetail: (Int) -> Unit,
    openAiComposeWithPost: (Int) -> Unit,
    openPoseRecommendWithPost: (Int) -> Unit,
    pickerMode: Boolean = false,
    onPickReference: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var searchInput by rememberSaveable(state.recommendationPlaceTag) {
        mutableStateOf(state.recommendationPlaceTag)
    }
    var selectedPhotoType by rememberSaveable { mutableStateOf("all") }
    var showPublishSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeletePostId by rememberSaveable { mutableStateOf<Int?>(null) }
    val publishPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        showPublishSheet = false
        uri?.toString()?.let {
            viewModel.prepareDirectPublish(it)
            openPublish()
        }
    }

    LaunchedEffect(authState.authenticated) {
        if (!authState.authenticated) return@LaunchedEffect
        viewModel.refreshCommunityFeed(reset = true)
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    BackHandler(onBack = openCapture)

    val visiblePosts = state.feed.filter { post ->
        val hitKeyword = searchInput.isBlank() || matchesPhotoTypeSearch(post, searchInput)
        val hitPhotoType = selectedPhotoType == "all" || primaryPhotoType(post) == selectedPhotoType
        hitKeyword && hitPhotoType
    }
    val pendingDeletePost = remember(state.feed, pendingDeletePostId) {
        state.feed.firstOrNull { it.id == pendingDeletePostId }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CamMatePage(
            title = "",
            showHeader = false,
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .offset(y = (-6).dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = searchInput,
                            onValueChange = {
                                searchInput = it
                                viewModel.updateRecommendationPlaceTag(it)
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("\u6309\u7167\u7247\u7c7b\u578b\u6216\u5173\u952e\u8bcd\u641c\u7d22") },
                            shape = RoundedCornerShape(18.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { viewModel.updateRecommendationPlaceTag(searchInput) },
                            ),
                        )
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                            tonalElevation = 1.dp,
                        ) {
                            IconButton(
                                onClick = { viewModel.updateRecommendationPlaceTag(searchInput) },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = "\u641c\u7d22",
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        communityPhotoTypes.forEach { type ->
                            FilterChip(
                                selected = selectedPhotoType == type.raw,
                                onClick = { selectedPhotoType = type.raw },
                                label = { Text(type.label) },
                            )
                        }
                    }
                }
            }

            item {
                if (state.loadingFeed) {
                    SectionCard {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (visiblePosts.isEmpty()) {
                    SectionCard {
                        Text("\u6682\u65e0\u5e16\u5b50", color = MaterialTheme.colorScheme.secondary)
                        Button(
                            onClick = { viewModel.refreshCommunityFeed(reset = true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("\u5237\u65b0")
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        visiblePosts.forEach { post ->
                            CommunityFeedCard(
                                post = post,
                                authHeader = state.authHeader,
                                canDelete = authState.user?.id == post.userId,
                                deleting = state.deletingPostIds.contains(post.id),
                                onClick = {
                                    if (pickerMode) {
                                        onPickReference?.invoke(post.id)
                                    } else {
                                        openPostDetail(post.id)
                                    }
                                },
                                onRelay = {
                                    if (pickerMode) {
                                        onPickReference?.invoke(post.id)
                                    } else {
                                        openAiComposeWithPost(post.id)
                                    }
                                },
                                onDeletePost = { pendingDeletePostId = post.id },
                                relayLabel = if (pickerMode) "选择" else "接力",
                            )
                        }
                        TextButton(
                            onClick = { viewModel.refreshCommunityFeed(reset = false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    state.loadingFeed -> "\u52a0\u8f7d\u4e2d..."
                                    state.feedHasMore -> "\u52a0\u8f7d\u66f4\u591a"
                                    else -> "\u6ca1\u6709\u66f4\u591a\u4e86"
                                },
                            )
                        }
                    }
                }
            }
        }

        if (!pickerMode) {
            FloatingActionButton(
                onClick = { showPublishSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 92.dp),
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "\u53d1\u5e03")
            }
        }

        if (!pickerMode && pendingDeletePost != null) {
            AlertDialog(
                onDismissRequest = { pendingDeletePostId = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletePostId?.let(viewModel::deletePost)
                            pendingDeletePostId = null
                        },
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletePostId = null }) {
                        Text("取消")
                    }
                },
                title = { Text("删除这条帖子？") },
                text = { Text("删除后，这条帖子会从社区和主页预览里一起移除。") },
            )
        }

        if (!pickerMode && showPublishSheet) {
            PublishEntrySheet(
                onDismiss = { showPublishSheet = false },
                onPickFromGallery = { publishPicker.launch("image/*") },
                onOpenCamera = {
                    showPublishSheet = false
                    openCamera()
                },
            )
        }
    }
}

@Composable
fun CommunityPostDetailScreen(
    viewModel: MainViewModel,
    postId: Int,
    onBackToCommunity: () -> Unit,
    openAiComposeWithPost: (Int) -> Unit,
    openPoseRecommendWithPost: (Int) -> Unit,
) {
    val context = LocalContext.current
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val state by viewModel.communityUiState.collectAsStateWithLifecycle()
    var showDeleteDialog by rememberSaveable(postId) { mutableStateOf(false) }
    var deleteRequested by rememberSaveable(postId) { mutableStateOf(false) }
    val post = state.feed.firstOrNull { it.id == postId }
        ?: state.recommendations.firstOrNull { it.post.id == postId }?.post
    val comments = state.commentsByPost[postId].orEmpty()
    val commentDraft = state.commentDraftByPost[postId].orEmpty()
    val commentsLoading = state.loadingCommentsPostId == postId

    LaunchedEffect(postId) {
        viewModel.loadComments(postId)
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearCommunityError()
        }
    }

    LaunchedEffect(post, deleteRequested) {
        if (deleteRequested && post == null) {
            deleteRequested = false
            onBackToCommunity()
        }
    }

    if (post == null) {
        CamMatePage(
            title = "\u5e16\u5b50\u8be6\u60c5",
            onBack = onBackToCommunity,
        ) {
            item {
                SectionCard {
                    Text("\u5e16\u5b50\u4e0d\u5b58\u5728", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        return
    }

    CamMatePage(
        title = "\u5e16\u5b50\u8be6\u60c5",
        onBack = onBackToCommunity,
    ) {
        item {
            CommunityDetailCard(
                post = post,
                authHeader = state.authHeader,
                canDelete = authState.user?.id == post.userId,
                deleting = state.deletingPostIds.contains(post.id),
                comments = comments,
                commentDraft = commentDraft,
                commentsLoading = commentsLoading,
                onToggleLike = { viewModel.togglePostLike(post) },
                onRelay = { openAiComposeWithPost(post.id) },
                onReference = { openPoseRecommendWithPost(post.id) },
                onDeletePost = { showDeleteDialog = true },
                onCommentDraftChange = { viewModel.updateCommentDraft(post.id, it) },
                onSubmitComment = { viewModel.submitComment(post.id) },
                onDeleteComment = { commentId -> viewModel.deleteComment(post.id, commentId) },
                onReloadComments = { viewModel.loadComments(post.id) },
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteRequested = true
                        viewModel.deletePost(postId)
                        showDeleteDialog = false
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
            title = { Text("删除这条帖子？") },
            text = { Text("删除后，社区和主页预览中的这条内容都会一起消失。") },
        )
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
    val request = rememberPostRequest(
        context = context,
        imageUrl = post.imageUrl,
        authHeader = authHeader,
    )

    SectionCard {
        AsyncImage(
            model = request,
            contentDescription = "community-tool-post-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = post.userNickname,
            fontWeight = FontWeight.SemiBold,
        )
        reason?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.secondary)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onUseAsReference, modifier = Modifier.weight(1f)) {
                Text("\u8bbe\u4e3a\u53c2\u8003")
            }
            Button(onClick = onRequestRemake, modifier = Modifier.weight(1f)) {
                Text("\u67e5\u770b\u53c2\u6570")
            }
        }
    }
}

@Composable
private fun CommunityFeedCard(
    post: CommunityPostItem,
    authHeader: String,
    canDelete: Boolean,
    deleting: Boolean,
    onClick: () -> Unit,
    onRelay: () -> Unit,
    onDeletePost: () -> Unit,
    relayLabel: String,
) {
    val context = LocalContext.current
    val request = rememberPostRequest(
        context = context,
        imageUrl = post.imageUrl,
        authHeader = authHeader,
    )

    SectionCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(name = post.userNickname)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = post.userNickname,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canDelete) {
                    TextButton(
                        onClick = onDeletePost,
                        enabled = !deleting,
                    ) {
                        Text(if (deleting) "删除中" else "删除")
                    }
                }
                TextButton(onClick = onRelay) {
                    Text(relayLabel)
                }
            }
        }
        AsyncImage(
            model = request,
            contentDescription = "community-post-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
        if (post.caption.isNotBlank()) {
            Text(
                text = post.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatText(
                icon = {
                    Icon(
                        Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                text = post.likeCount.toString(),
            )
            StatText(
                icon = {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                text = post.commentCount.toString(),
            )
        }
    }
}

@Composable
private fun CommunityDetailCard(
    post: CommunityPostItem,
    authHeader: String,
    canDelete: Boolean,
    deleting: Boolean,
    comments: List<CommunityCommentItem>,
    commentDraft: String,
    commentsLoading: Boolean,
    onToggleLike: () -> Unit,
    onRelay: () -> Unit,
    onReference: () -> Unit,
    onDeletePost: () -> Unit,
    onCommentDraftChange: (String) -> Unit,
    onSubmitComment: () -> Unit,
    onDeleteComment: (Int) -> Unit,
    onReloadComments: () -> Unit,
) {
    val context = LocalContext.current
    val request = rememberPostRequest(
        context = context,
        imageUrl = post.imageUrl,
        authHeader = authHeader,
    )

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(name = post.userNickname)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = post.userNickname,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${post.placeTag} · ${primaryPhotoTypeLabel(post)} · ${formatFeedTime(post.createdAt)}",
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canDelete) {
                    TextButton(
                        onClick = onDeletePost,
                        enabled = !deleting,
                    ) {
                        Text(if (deleting) "删除中" else "删除")
                    }
                }
                TextButton(onClick = onRelay) {
                    Text("\u63a5\u529b")
                }
            }
        }

        AsyncImage(
            model = request,
            contentDescription = "community-detail-${post.id}",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )

        if (post.caption.isNotBlank()) {
            Text(post.caption)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onToggleLike,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (post.likedByMe) "\u5df2\u8d5e ${post.likeCount}" else "\u70b9\u8d5e ${post.likeCount}")
            }
            Button(
                onClick = onReference,
                modifier = Modifier.weight(1f),
            ) {
                Text("\u53c2\u8003")
            }
        }

        Text(
            text = "\u8bc4\u8bba ${post.commentCount}",
            style = MaterialTheme.typography.titleSmall,
        )
        if (commentsLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (comments.isEmpty()) {
            TextButton(onClick = onReloadComments) {
                Text("\u6682\u65e0\u8bc4\u8bba")
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                comments.forEach { comment ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = comment.userNickname,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(comment.text)
                        }
                        if (comment.canDelete) {
                            TextButton(onClick = { onDeleteComment(comment.id) }) {
                                Text("\u5220\u9664")
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = commentDraft,
            onValueChange = onCommentDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("\u53d1\u8868\u8bc4\u8bba") },
            maxLines = 3,
        )
        Button(
            onClick = onSubmitComment,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("\u53d1\u9001")
        }
    }
}

@Composable
private fun UserAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).ifBlank { "C" },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatText(
    icon: @Composable () -> Unit,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(text, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun rememberPostRequest(
    context: android.content.Context,
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

private fun sceneLabel(raw: String): String {
    return when (raw.lowercase()) {
        "general" -> "\u901a\u7528"
        "portrait" -> "\u4eba\u50cf"
        "landscape" -> "\u98ce\u666f"
        "food" -> "\u7f8e\u98df"
        "pet" -> "\u5ba0\u7269"
        "flower" -> "\u82b1\u5349"
        "night" -> "\u591c\u666f"
        else -> "\u901a\u7528"
    }
}

private fun primaryPhotoTypeLabel(post: CommunityPostItem): String {
    val primaryType = primaryPhotoType(post)
    return communityPhotoTypes.firstOrNull { it.raw == primaryType }?.label ?: sceneLabel(primaryType)
}

private fun primaryPhotoType(post: CommunityPostItem): String {
    val normalized = post.sceneType.trim().lowercase()
    return communityPhotoTypes.firstOrNull { it.raw == normalized }?.raw ?: "general"
}

private fun matchesPhotoTypeSearch(post: CommunityPostItem, keyword: String): Boolean {
    val normalizedKeyword = keyword.trim().lowercase()
    if (normalizedKeyword.isBlank()) return true

    val option = communityPhotoTypes.firstOrNull { it.raw == primaryPhotoType(post) }
    val photoTypeTexts = if (option == null) {
        listOf(primaryPhotoType(post))
    } else {
        listOf(option.raw, option.label) + option.aliases
    }
    val searchableTexts = buildList {
        add(post.sceneType)
        add(sceneLabel(post.sceneType))
        add(post.placeTag)
        add(post.caption)
        add(post.reviewText)
        addAll(photoTypeTexts)
    }.joinToString(" ").lowercase()
    return searchableTexts.contains(normalizedKeyword)
}

private fun formatFeedTime(epochSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val diff = (now - epochSec).coerceAtLeast(0L)
    return when {
        diff < 60L -> "\u521a\u521a"
        diff < 3600L -> "${diff / 60L} \u5206\u949f\u524d"
        diff < 86400L -> "${diff / 3600L} \u5c0f\u65f6\u524d"
        else -> "${diff / 86400L} \u5929\u524d"
    }
}

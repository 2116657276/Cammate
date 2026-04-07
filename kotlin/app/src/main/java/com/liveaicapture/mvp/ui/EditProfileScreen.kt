package com.liveaicapture.mvp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.liveaicapture.mvp.ui.components.CamMatePage
import com.liveaicapture.mvp.ui.components.PublishEntrySheet

private enum class ProfileEditTarget {
    Nickname,
    Bio,
}

@Composable
fun EditProfileScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    openCamera: () -> Unit,
) {
    val authState by viewModel.authUiState.collectAsStateWithLifecycle()
    val user = authState.user
    val nickname = user?.nickname.orEmpty().ifBlank { "用户名" }
    val bio = user?.bio.orEmpty()
    val avatarUri = user?.avatarUri

    var showAvatarSheet by rememberSaveable { mutableStateOf(false) }
    var editTarget by rememberSaveable { mutableStateOf<ProfileEditTarget?>(null) }
    var draftText by rememberSaveable { mutableStateOf("") }

    val avatarPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        showAvatarSheet = false
        viewModel.updateProfileAvatarUri(uri?.toString())
    }

    CamMatePage(
        title = "",
        showHeader = false,
        horizontalPadding = 0.dp,
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Text("返回")
                    }
                    Text(
                        text = "我的资料",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                EditableProfileAvatar(
                    nickname = nickname,
                    avatarUri = avatarUri,
                )

                Surface(
                    modifier = Modifier.clickable { showAvatarSheet = true },
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = "更换头像",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EditableProfileRow(
                        label = "昵称",
                        value = nickname,
                        onClick = {
                            draftText = nickname
                            editTarget = ProfileEditTarget.Nickname
                        },
                    )
                    EditableProfileRow(
                        label = "简介",
                        value = bio.ifBlank { "暂时没有简介" },
                        onClick = {
                            draftText = bio
                            editTarget = ProfileEditTarget.Bio
                        },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Button(
                        onClick = { viewModel.switchAccount() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Outlined.PersonOutline,
                            contentDescription = "切换账号",
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "切换账号",
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                        )
                    }
                    Button(
                        onClick = { viewModel.logout() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = "退出登录",
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "退出登录",
                            modifier = Modifier.padding(start = 6.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (showAvatarSheet) {
        PublishEntrySheet(
            title = "更换头像",
            onDismiss = { showAvatarSheet = false },
            onPickFromGallery = { avatarPicker.launch("image/*") },
            onOpenCamera = {
                showAvatarSheet = false
                openCamera()
            },
        )
    }

    if (editTarget != null) {
        val title = if (editTarget == ProfileEditTarget.Nickname) "修改昵称" else "修改简介"
        val placeholder = if (editTarget == ProfileEditTarget.Nickname) "请输入昵称" else "请输入简介"
        AlertDialog(
            onDismissRequest = { editTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editTarget == ProfileEditTarget.Nickname) {
                            viewModel.updateProfileNickname(draftText)
                        } else {
                            viewModel.updateProfileBio(draftText)
                        }
                        editTarget = null
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text("取消")
                }
            },
            title = {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    singleLine = editTarget == ProfileEditTarget.Nickname,
                    maxLines = if (editTarget == ProfileEditTarget.Nickname) 1 else 3,
                )
            },
        )
    }
}

@Composable
private fun EditableProfileAvatar(
    nickname: String,
    avatarUri: String?,
) {
    Box(
        modifier = Modifier
            .size(136.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            )
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUri.isNullOrBlank()) {
            Text(
                text = nickname.take(1).ifBlank { "我" },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(avatarUri)
                    .crossfade(true)
                    .build(),
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun EditableProfileRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "$label：",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ">",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

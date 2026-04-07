package com.liveaicapture.mvp.ui

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.liveaicapture.mvp.data.RetouchMode
import com.liveaicapture.mvp.data.RetouchPreset
import com.liveaicapture.mvp.data.RetouchUiState
import com.liveaicapture.mvp.ui.components.CamMatePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetouchScreen(
    viewModel: MainViewModel,
    onBackToCamera: () -> Unit,
    openFeedback: () -> Unit,
) {
    val context = LocalContext.current
    val retouchState by viewModel.retouchUiState.collectAsStateWithLifecycle()
    val feedbackState by viewModel.feedbackUiState.collectAsStateWithLifecycle()

    LaunchedEffect(feedbackState.visible) {
        if (feedbackState.visible) {
            openFeedback()
        }
    }
    LaunchedEffect(retouchState.errorMessage) {
        retouchState.errorMessage?.takeIf { it.isNotBlank() }?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        }
    }

    CamMatePage(
        title = "",
        showHeader = false,
        horizontalPadding = 18.dp,
    ) {
        item {
            CompactPageHeader(
                title = "\u0041\u0049 \u4fee\u56fe",
                subtitle = "\u6a21\u677f\u5feb\u901f\u51fa\u7247\uff0c\u81ea\u5b9a\u4e49\u53ef\u4ee5\u7ee7\u7eed\u7cbe\u8c03",
                onBack = onBackToCamera,
            )
        }

        item {
            RetouchPreviewCard(state = retouchState)
        }

        item {
            RetouchControlSection(
                state = retouchState,
                onModeChange = viewModel::updateRetouchMode,
                onPresetChange = viewModel::updateRetouchPreset,
                onPromptChange = viewModel::updateRetouchCustomPrompt,
                onStrengthChange = viewModel::updateRetouchStrength,
                onApply = viewModel::applyRetouch,
                onSkip = viewModel::continueWithOriginalPhoto,
                onBackToOriginal = viewModel::restartRetouchFromOriginal,
                onGoFeedback = viewModel::continueWithRetouchedPhoto,
            )
        }
    }
}

@Composable
private fun CompactPageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier.padding(start = 12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
            tonalElevation = 1.dp,
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "\u8fd4\u56de",
                )
            }
        }
    }
}

@Composable
private fun RetouchPreviewCard(
    state: RetouchUiState,
) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 520.dp)
            .aspectRatio(state.previewAspectRatio.coerceIn(0.55f, 1.8f))
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                    ),
                ),
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = shape,
            ),
    ) {
        val imageModel: Any? = state.previewBase64?.takeIf { it.isNotBlank() }?.let {
            android.util.Base64.decode(it, android.util.Base64.DEFAULT)
        } ?: state.originalPhotoUri

        AsyncImage(
            model = imageModel,
            contentDescription = "\u4fee\u56fe\u9884\u89c8",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        ) {
            Text(
                text = if (state.previewBase64.isNullOrBlank()) {
                    "\u539f\u56fe"
                } else {
                    "\u4fee\u56fe\u9884\u89c8"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RetouchControlSection(
    state: RetouchUiState,
    onModeChange: (RetouchMode) -> Unit,
    onPresetChange: (RetouchPreset) -> Unit,
    onPromptChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    onBackToOriginal: () -> Unit,
    onGoFeedback: () -> Unit,
) {
    val hasPreview = !state.previewBase64.isNullOrBlank()
    val canSubmit = !state.applying && (
        state.mode == RetouchMode.TEMPLATE ||
            state.customPrompt.isNotBlank()
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RetouchModeSelectorRow(
            state = state,
            onModeChange = onModeChange,
        )

        RetouchOptionPanel(
            state = state,
            onPresetChange = onPresetChange,
            onPromptChange = onPromptChange,
            onStrengthChange = onStrengthChange,
        )

        if (state.applying) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape),
                )
                Text(
                    text = "\u6b63\u5728\u751f\u6210\u4fee\u56fe\u7ed3\u679c\uff0c\u8bf7\u7a0d\u7b49\u7247\u523b",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.errorMessage?.let {
            Text(
                text = it,
                color = Color(0xFFB42318),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FilledTonalButton(
                onClick = onApply,
                enabled = canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.applying) "\u5904\u7406\u4e2d..." else "\u5f00\u59cb\u4fee\u56fe")
            }
            OutlinedButton(
                onClick = onSkip,
                enabled = !state.applying,
                modifier = Modifier.weight(1f),
            ) {
                Text("\u8df3\u8fc7\u4fee\u56fe")
            }
        }

        if (hasPreview) {
            Text(
                text = "\u5f53\u524d\u5df2\u663e\u793a\u6700\u65b0\u4fee\u56fe\u7ed3\u679c\uff0c\u53ef\u4ee5\u91cd\u65b0\u8c03\u6574\u6548\u679c\u540e\u518d\u6b21\u63d0\u4ea4\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onBackToOriginal,
                    enabled = !state.applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("\u56de\u5230\u539f\u56fe")
                }
                FilledTonalButton(
                    onClick = onGoFeedback,
                    enabled = !state.applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("\u524d\u5f80\u53cd\u9988")
                }
            }
        }
    }
}

@Composable
private fun RetouchModeSelectorRow(
    state: RetouchUiState,
    onModeChange: (RetouchMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RetouchModeButton(
            modifier = Modifier.weight(1f),
            selected = state.mode == RetouchMode.TEMPLATE,
            enabled = !state.applying,
            title = "\u6a21\u677f",
            icon = Icons.Filled.AutoAwesome,
            onClick = { onModeChange(RetouchMode.TEMPLATE) },
        )
        RetouchModeButton(
            modifier = Modifier.weight(1f),
            selected = state.mode == RetouchMode.CUSTOM,
            enabled = !state.applying,
            title = "\u81ea\u5b9a\u4e49",
            icon = Icons.Filled.Palette,
            onClick = { onModeChange(RetouchMode.CUSTOM) },
        )
    }
}

@Composable
private fun RetouchOptionPanel(
    state: RetouchUiState,
    onPresetChange: (RetouchPreset) -> Unit,
    onPromptChange: (String) -> Unit,
    onStrengthChange: (Float) -> Unit,
) {
    InlinePanel {
        if (state.mode == RetouchMode.TEMPLATE) {
            Text(
                text = "\u6a21\u677f\u98ce\u683c",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.preset.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RetouchPreset.entries.forEach { preset ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = state.preset == preset,
                        enabled = !state.applying,
                        onClick = { onPresetChange(preset) },
                        label = { Text(preset.displayName()) },
                    )
                }
            }
        } else {
            Text(
                text = "\u81ea\u5b9a\u4e49\u63d0\u793a",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = state.customPrompt,
                onValueChange = onPromptChange,
                enabled = !state.applying,
                label = { Text("\u63d0\u793a\u8bcd") },
                placeholder = { Text("\u4f8b\u5982\uff1a\u7ec6\u5316\u80a4\u8272\uff0c\u538b\u4f4e\u6742\u4e71\u80cc\u666f\uff0c\u7a81\u51fa\u4eba\u7269\u5149\u5f71") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 4,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "\u4fee\u56fe\u5f3a\u5ea6",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Text(
                    text = "${(state.strength * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Slider(
            value = state.strength,
            onValueChange = onStrengthChange,
            valueRange = 0.15f..0.85f,
            enabled = !state.applying,
        )
        Text(
            text = "\u5f53\u524d\u573a\u666f\uff1a${state.sceneHint.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RetouchModeButton(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(52.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.height(52.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun InlinePanel(
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
        tonalElevation = 1.dp,
        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

private fun RetouchPreset.displayName(): String = when (this) {
    RetouchPreset.BG_CLEANUP -> "\u80cc\u666f\u51c0\u5316"
    RetouchPreset.PORTRAIT_BEAUTY -> "\u4eba\u50cf\u7f8e\u5316"
    RetouchPreset.COLOR_GRADE -> "\u7535\u5f71\u8c03\u8272"
}

private fun RetouchPreset.description(): String = when (this) {
    RetouchPreset.BG_CLEANUP -> "\u9002\u5408\u5feb\u901f\u6e05\u7406\u80cc\u666f\u5e72\u6270\uff0c\u8ba9\u4e3b\u4f53\u66f4\u5e72\u51c0\u3002"
    RetouchPreset.PORTRAIT_BEAUTY -> "\u4f18\u5148\u8c03\u6574\u4eba\u7269\u80a4\u611f\u3001\u5149\u7ebf\u548c\u7ec6\u8282\uff0c\u6210\u7247\u66f4\u67d4\u548c\u3002"
    RetouchPreset.COLOR_GRADE -> "\u52a0\u5f3a\u753b\u9762\u8272\u8c03\u548c\u6c14\u6c1b\uff0c\u66f4\u9002\u5408\u60f3\u8981\u8d28\u611f\u611f\u7684\u6210\u7247\u3002"
}

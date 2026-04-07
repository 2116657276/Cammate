package com.liveaicapture.mvp.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import com.liveaicapture.mvp.data.CaptureMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    backToCapture: () -> Unit,
    openRetouch: () -> Unit,
    openFeedback: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rollDegrees = rememberDeviceRollDegrees()
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.toString()?.let(viewModel::onGalleryPhotoSelected)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val analysisExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var activeCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var lens by rememberSaveable { mutableStateOf(CameraLens.BACK) }
    var flash by rememberSaveable { mutableStateOf(FlashMode.OFF) }
    var timerSec by rememberSaveable { mutableIntStateOf(0) }
    var zoomRatio by rememberSaveable { mutableFloatStateOf(1f) }
    var minZoomRatio by rememberSaveable { mutableFloatStateOf(0.5f) }
    var maxZoomRatio by rememberSaveable { mutableFloatStateOf(5f) }
    var exposureIndex by rememberSaveable { mutableIntStateOf(0) }
    var minExposureIndex by rememberSaveable { mutableIntStateOf(0) }
    var maxExposureIndex by rememberSaveable { mutableIntStateOf(0) }
    var exposureStepEv by rememberSaveable { mutableFloatStateOf(1f) }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var hasFlashUnit by rememberSaveable { mutableStateOf(false) }
    var activeSheet by rememberSaveable { mutableStateOf<CameraSettingSheet?>(null) }
    var aiPanelDetailed by rememberSaveable { mutableStateOf(false) }
    var flashMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        viewModel.onCameraSessionEntered()
        onDispose {
            viewModel.onCameraSessionExited()
            captureJob?.cancel()
            analysisExecutor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                captureJob?.cancel()
                captureJob = null
                countdown = 0
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(activeCamera, zoomRatio, minZoomRatio, maxZoomRatio) {
        val camera = activeCamera ?: return@LaunchedEffect
        val safeZoom = zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
        if (safeZoom != zoomRatio) zoomRatio = safeZoom
        camera.cameraControl.setZoomRatio(safeZoom)
    }

    DisposableEffect(activeCamera, lifecycleOwner) {
        val camera = activeCamera
        if (camera == null) {
            onDispose { }
        } else {
            val zoomLiveData = camera.cameraInfo.zoomState
            val observer = Observer<androidx.camera.core.ZoomState> { zoomState ->
                val hwMin = zoomState.minZoomRatio
                val hwMax = zoomState.maxZoomRatio
                val uiMin = max(0.5f, hwMin)
                val rawUiMax = min(5f, hwMax)
                val uiMax = if (rawUiMax < uiMin) uiMin else rawUiMax
                minZoomRatio = uiMin
                maxZoomRatio = uiMax
                val safeZoom = zoomRatio.coerceIn(uiMin, uiMax)
                if (safeZoom != zoomRatio) {
                    zoomRatio = safeZoom
                }
            }
            zoomLiveData.observe(lifecycleOwner, observer)
            onDispose {
                zoomLiveData.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(activeCamera, exposureIndex, minExposureIndex, maxExposureIndex) {
        val camera = activeCamera ?: return@LaunchedEffect
        if (maxExposureIndex <= minExposureIndex) return@LaunchedEffect
        val safeIndex = exposureIndex.coerceIn(minExposureIndex, maxExposureIndex)
        if (safeIndex != exposureIndex) exposureIndex = safeIndex
        camera.cameraControl.setExposureCompensationIndex(safeIndex)
    }

    LaunchedEffect(activeCamera, torchEnabled, hasFlashUnit, lens) {
        val camera = activeCamera ?: return@LaunchedEffect
        val enabled = lens == CameraLens.BACK && hasFlashUnit && torchEnabled
        camera.cameraControl.enableTorch(enabled)
    }

    LaunchedEffect(flash) {
        if (flash != FlashMode.OFF && torchEnabled) {
            torchEnabled = false
        }
    }

    LaunchedEffect(torchEnabled) {
        if (torchEnabled && flash != FlashMode.OFF) {
            flash = FlashMode.OFF
        }
    }

    CameraCaptureLayout(
        uiState = uiState,
        rollDegrees = rollDegrees,
        hasCameraPermission = hasCameraPermission,
        lens = lens,
        flash = flash,
        torchEnabled = torchEnabled,
        hasFlashUnit = hasFlashUnit,
        zoomRatio = zoomRatio,
        exposureIndex = exposureIndex,
        minExposureIndex = minExposureIndex,
        maxExposureIndex = maxExposureIndex,
        exposureStepEv = exposureStepEv,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        timerSec = timerSec,
        countdown = countdown,
        imageCaptureReady = imageCapture != null,
        aiPanelDetailed = aiPanelDetailed,
        flashMenuExpanded = flashMenuExpanded,
        activeSheet = activeSheet,
        onBack = backToCapture,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onToggleAiPanel = { aiPanelDetailed = !aiPanelDetailed },
        onDismissFlashMenu = { flashMenuExpanded = false },
        onOpenFlashMenu = { flashMenuExpanded = true },
        onFlashOff = {
            flash = FlashMode.OFF
            torchEnabled = false
            flashMenuExpanded = false
        },
        onFlashOn = {
            flash = FlashMode.ON
            torchEnabled = false
            flashMenuExpanded = false
        },
        onTorchOn = {
            torchEnabled = true
            flash = FlashMode.OFF
            flashMenuExpanded = false
        },
        onRequestAnalyze = { viewModel.requestAiAnalyze() },
        onOpenExposure = {
            activeSheet = if (activeSheet == CameraSettingSheet.EXPOSURE) null else CameraSettingSheet.EXPOSURE
        },
        onOpenZoom = {
            activeSheet = if (activeSheet == CameraSettingSheet.ZOOM) null else CameraSettingSheet.ZOOM
        },
        onOpenTimer = {
            activeSheet = if (activeSheet == CameraSettingSheet.TIMER) null else CameraSettingSheet.TIMER
        },
        onTimerSecChange = { timerSec = it },
        onZoomRatioChange = { zoomRatio = it },
        onExposureIndexChange = { exposureIndex = it },
        onSelectCaptureMode = { mode -> viewModel.updateCaptureMode(mode) },
        onOpenGallery = { galleryPicker.launch("image/*") },
        onSwitchLens = {
            val nextLens = if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
            lens = nextLens
            if (nextLens == CameraLens.FRONT) {
                flash = FlashMode.OFF
                torchEnabled = false
            }
        },
        onCapture = {
            if (captureJob == null) {
                captureJob = scope.launch {
                    if (timerSec > 0) {
                        for (left in timerSec downTo 1) {
                            countdown = left
                            delay(1000)
                        }
                    }
                    countdown = 0
                    capturePhoto(
                        context = context,
                        imageCapture = imageCapture,
                        flashMode = flash,
                        lens = lens,
                    ) { uriString ->
                        viewModel.onPhotoCaptured(uriString)
                        openRetouch()
                    }
                    countdown = 0
                }.also { job ->
                    job.invokeOnCompletion {
                        captureJob = null
                        countdown = 0
                    }
                }
            }
        },
        previewContent = {
            if (hasCameraPermission) {
                key(lens) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                // Use a fit-based preview so the live framing matches the saved photo more closely.
                                scaleType = PreviewView.ScaleType.FIT_CENTER
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                bindUseCases(
                                    context = ctx,
                                    lifecycleOwner = lifecycleOwner,
                                    previewView = this,
                                    analysisExecutor = analysisExecutor,
                                    lens = lens,
                                    flashMode = flash,
                                    onAnalyze = { imageProxy ->
                                        viewModel.onFrame(
                                            imageProxy = imageProxy,
                                            lensFacing = if (lens == CameraLens.FRONT) "front" else "back",
                                        )
                                    },
                                    onImageCaptureReady = { capture -> imageCapture = capture },
                                    onCameraReady = { camera ->
                                        activeCamera = camera

                                        hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                                        if (!hasFlashUnit || lens == CameraLens.FRONT) {
                                            flash = FlashMode.OFF
                                            torchEnabled = false
                                        }

                                        val exposureState = camera.cameraInfo.exposureState
                                        minExposureIndex = exposureState.exposureCompensationRange.lower
                                        maxExposureIndex = exposureState.exposureCompensationRange.upper
                                        exposureStepEv = exposureState.exposureCompensationStep.toFloat()
                                        exposureIndex = exposureIndex.coerceIn(minExposureIndex, maxExposureIndex)
                                    },
                                )
                            }
                        },
                    )
                }
                CameraOverlay(state = uiState.overlay, rollDegrees = rollDegrees)
            }
        },
    )

}

@Composable
private fun CameraCaptureLayout(
    uiState: com.liveaicapture.mvp.data.CameraUiState,
    rollDegrees: Float,
    hasCameraPermission: Boolean,
    lens: CameraLens,
    flash: FlashMode,
    torchEnabled: Boolean,
    hasFlashUnit: Boolean,
    zoomRatio: Float,
    exposureIndex: Int,
    minExposureIndex: Int,
    maxExposureIndex: Int,
    exposureStepEv: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    timerSec: Int,
    countdown: Int,
    imageCaptureReady: Boolean,
    aiPanelDetailed: Boolean,
    flashMenuExpanded: Boolean,
    activeSheet: CameraSettingSheet?,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onToggleAiPanel: () -> Unit,
    onDismissFlashMenu: () -> Unit,
    onOpenFlashMenu: () -> Unit,
    onFlashOff: () -> Unit,
    onFlashOn: () -> Unit,
    onTorchOn: () -> Unit,
    onRequestAnalyze: () -> Unit,
    onOpenExposure: () -> Unit,
    onOpenZoom: () -> Unit,
    onOpenTimer: () -> Unit,
    onTimerSecChange: (Int) -> Unit,
    onZoomRatioChange: (Float) -> Unit,
    onExposureIndexChange: (Int) -> Unit,
    onSelectCaptureMode: (CaptureMode) -> Unit,
    onOpenGallery: () -> Unit,
    onSwitchLens: () -> Unit,
    onCapture: () -> Unit,
    previewContent: @Composable BoxScope.() -> Unit,
) {
    val scenePrompt = "${uiState.detectedScene.label} ${"%.0f".format(uiState.sceneConfidence * 100)}%"
    val flashSummary = when {
        torchEnabled -> "常亮"
        flash == FlashMode.ON -> "开启"
        else -> "关闭"
    }
    val aiSuggestionText = if (uiState.analyzingTips) "AI 正在思考，请保持画面稳定" else uiState.tipText
    val parameterIconAnchorWidth = 28.dp
    val parameterPanelWidth = when (activeSheet) {
        CameraSettingSheet.TIMER -> 118.dp
        CameraSettingSheet.EXPOSURE -> 96.dp
        else -> 46.dp
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraBackButton(onClick = onBack)

                Box {
                    CameraCircleActionButton(
                        icon = Icons.Outlined.FlashOn,
                        contentDescription = "闪光灯",
                        tint = if (flash != FlashMode.OFF || torchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        onClick = onOpenFlashMenu,
                    )
                    DropdownMenu(
                        expanded = flashMenuExpanded,
                        onDismissRequest = onDismissFlashMenu,
                    ) {
                        DropdownMenuItem(text = { Text("关闭") }, onClick = onFlashOff)
                        DropdownMenuItem(
                            text = { Text("开启") },
                            enabled = hasFlashUnit && lens == CameraLens.BACK,
                            onClick = onFlashOn,
                        )
                        DropdownMenuItem(
                            text = { Text("常亮") },
                            enabled = hasFlashUnit && lens == CameraLens.BACK,
                            onClick = onTorchOn,
                        )
                        DropdownMenuItem(
                            text = { Text("当前：$flashSummary") },
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraCapsuleLabel(
                    text = "场景识别  $scenePrompt",
                    modifier = Modifier.weight(1f),
                )
                CameraCapsuleButton(
                    text = if (uiState.analyzingTips) "分析中" else "AI建议",
                    icon = Icons.Outlined.AutoAwesome,
                    enabled = !uiState.analyzingTips,
                    modifier = Modifier.weight(1f),
                    onClick = onRequestAnalyze,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.78f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(parameterPanelWidth))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.995f)
                            .fillMaxHeight(0.98f)
                            .aspectRatio(3f / 4f, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF101010)),
                    ) {
                        previewContent()

                        if (!hasCameraPermission) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("需要相机权限", color = Color.White)
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(onClick = onRequestPermission) {
                                    Text("授权")
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 18.dp, top = 12.dp, end = 14.dp)
                                .width(228.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Color(0xC01E2A35),
                            tonalElevation = 0.dp,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f), RoundedCornerShape(18.dp))
                                    .clickable(onClick = onToggleAiPanel)
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "AI建议",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                    )
                                }
                                Text(
                                    text = aiSuggestionText,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = if (aiPanelDetailed) 7 else 4,
                                )
                                if (uiState.moveHintText.isNotBlank()) {
                                    Text(
                                        text = uiState.moveHintText,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.96f),
                                        fontSize = 13.sp,
                                        lineHeight = 17.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = if (aiPanelDetailed) 4 else 2,
                                    )
                                }
                                if (aiPanelDetailed) {
                                    Text(uiState.statusText, color = Color.White.copy(alpha = 0.82f), fontSize = 10.sp)
                                    Text(
                                        text = "稳定 ${"%.0f".format(uiState.stabilityScore * 100)}%  倾斜 ${"%.1f".format(abs(rollDegrees))}°",
                                        color = Color.White.copy(alpha = 0.82f),
                                        fontSize = 10.sp,
                                    )
                                }
                                if (uiState.analyzingTips) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }

                        if (activeSheet == CameraSettingSheet.ZOOM) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0x661E2A35),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "焦距 ${"%.1f".format(zoomRatio)}x",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 12.sp,
                                    )
                                    Slider(
                                        value = zoomRatio,
                                        onValueChange = onZoomRatioChange,
                                        valueRange = minZoomRatio..maxZoomRatio,
                                    )
                                }
                            }
                        }

                        if (countdown > 0) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .clip(CircleShape),
                                color = Color(0xB8000000),
                            ) {
                                Text(
                                    text = countdown.toString(),
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                                    color = Color.White,
                                    style = MaterialTheme.typography.displayMedium,
                                )
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .width(parameterPanelWidth)
                        .fillMaxHeight(0.98f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.End,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (activeSheet == CameraSettingSheet.EXPOSURE) {
                            CameraVerticalExposureRuler(
                                modifier = Modifier
                                    .fillMaxHeight(0.96f)
                                    .width(84.dp)
                                    .padding(end = 2.dp),
                                currentIndex = exposureIndex,
                                minIndex = minExposureIndex,
                                maxIndex = maxExposureIndex,
                                stepEv = exposureStepEv,
                                onValueChange = { onExposureIndexChange(it) },
                            )
                        } else if (activeSheet == CameraSettingSheet.TIMER) {
                            CameraTimerOptions(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                timerSec = timerSec,
                                onTimerSecChange = onTimerSecChange,
                            )
                        }

                        Column(
                            modifier = Modifier.width(parameterIconAnchorWidth),
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CameraCircleActionButton(
                                icon = Icons.Outlined.Exposure,
                                contentDescription = "曝光",
                                size = 24.dp,
                                iconSize = 20.dp,
                                tint = if (activeSheet == CameraSettingSheet.EXPOSURE || exposureIndex != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                onClick = onOpenExposure,
                            )
                            CameraCircleActionButton(
                                icon = Icons.Outlined.ZoomIn,
                                contentDescription = "焦距",
                                size = 24.dp,
                                iconSize = 20.dp,
                                tint = if (activeSheet == CameraSettingSheet.ZOOM || zoomRatio > 1.05f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                onClick = onOpenZoom,
                            )
                            CameraCircleActionButton(
                                icon = Icons.Outlined.Timer,
                                contentDescription = "延时",
                                size = 24.dp,
                                iconSize = 20.dp,
                                tint = if (activeSheet == CameraSettingSheet.TIMER || timerSec > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                onClick = onOpenTimer,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureMode.entries.forEach { mode ->
                    CameraTagBox(
                        text = mode.label,
                        selected = uiState.settings.captureMode == mode,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelectCaptureMode(mode) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraCircleActionButton(
                        icon = Icons.Outlined.Image,
                        contentDescription = "系统图库",
                        size = 34.dp,
                        iconSize = 30.dp,
                        tint = MaterialTheme.colorScheme.onBackground,
                        onClick = onOpenGallery,
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraCircleActionButton(
                        icon = Icons.Outlined.CameraAlt,
                        contentDescription = "拍摄",
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .border(3.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f), CircleShape),
                        size = 90.dp,
                        iconSize = 46.dp,
                        tint = Color.White,
                        onClick = {
                            if (hasCameraPermission && imageCaptureReady) {
                                onCapture()
                            }
                        },
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CameraCircleActionButton(
                        icon = Icons.Outlined.Cameraswitch,
                        contentDescription = if (lens == CameraLens.BACK) "切换前置" else "切换后置",
                        size = 34.dp,
                        iconSize = 30.dp,
                        tint = MaterialTheme.colorScheme.onBackground,
                        onClick = onSwitchLens,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberDeviceRollDegrees(): Float {
    val context = LocalContext.current
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val rollState = remember { mutableFloatStateOf(0f) }

    DisposableEffect(sensorManager) {
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector == null) {
            onDispose { }
        } else {
            val rotationMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    val rollRad = orientation[2]
                    val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat().coerceIn(-45f, 45f)
                    rollState.floatValue = rollDeg
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }
    return rollState.floatValue
}

private enum class CameraLens { BACK, FRONT }

private enum class FlashMode(val label: String, val imageCaptureMode: Int) {
    OFF("关闭", ImageCapture.FLASH_MODE_OFF),
    AUTO("自动", ImageCapture.FLASH_MODE_AUTO),
    ON("开启", ImageCapture.FLASH_MODE_ON),
}

private enum class CameraSettingSheet {
    FLASH,
    ZOOM,
    EXPOSURE,
    TIMER,
}

@Composable
private fun CameraCircleActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
    iconSize: Dp = 22.dp,
    tint: Color = Color.White,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun CameraBackButton(onClick: () -> Unit) {
    CameraCircleActionButton(
        icon = Icons.Outlined.ArrowBack,
        contentDescription = "返回",
        size = 28.dp,
        iconSize = 26.dp,
        tint = Color.White,
        onClick = onClick,
    )
}

@Composable
private fun CameraCapsuleLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Text(
            text = text,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CameraCapsuleButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = text,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CameraTagBox(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) Color(0x3322D3EE) else Color(0x22111111),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .border(
                    1.dp,
                    if (selected) Color(0xFF82F3E7) else Color(0x55FFFFFF),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun CameraVerticalExposureRuler(
    currentIndex: Int,
    minIndex: Int,
    maxIndex: Int,
    stepEv: Float,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val clampedIndex = currentIndex.coerceIn(minIndex, maxIndex)
        val selectableRange = (maxIndex - minIndex).coerceAtLeast(0)
        val topPadding = 18.dp
        val bottomPadding = 18.dp
        val topPaddingPx = with(density) { topPadding.toPx() }
        val bottomPaddingPx = with(density) { bottomPadding.toPx() }
        val trackHeightPx = with(density) { (maxHeight - topPadding - bottomPadding).toPx() }.coerceAtLeast(1f)
        val activeColor = MaterialTheme.colorScheme.primary
        val thumbY = if (selectableRange == 0) {
            topPaddingPx + trackHeightPx / 2f
        } else {
            val fractionFromTop = (maxIndex - clampedIndex).toFloat() / selectableRange.toFloat()
            topPaddingPx + trackHeightPx * fractionFromTop
        }
        val minLabel = formatExposureValue(minIndex, stepEv)
        val zeroLabel = formatExposureValue(0, stepEv)
        val maxLabel = formatExposureValue(maxIndex, stepEv)
        val thumbOffsetY = with(density) { thumbY.toDp() - 14.dp }

        fun positionToIndex(y: Float): Int {
            if (selectableRange == 0) return clampedIndex
            val clampedY = y.coerceIn(topPaddingPx, topPaddingPx + trackHeightPx)
            val fractionFromTop = (clampedY - topPaddingPx) / trackHeightPx
            val rawIndex = maxIndex - (fractionFromTop * selectableRange)
            return rawIndex.roundToInt().coerceIn(minIndex, maxIndex)
        }

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(maxLabel, color = Color.White, fontSize = 10.sp)
            Text(zeroLabel, color = Color.White.copy(alpha = 0.88f), fontSize = 10.sp)
            Text(minLabel, color = Color.White, fontSize = 10.sp)
        }

        Canvas(
            modifier = Modifier
                .fillMaxHeight()
                .width(44.dp)
                .pointerInput(minIndex, maxIndex) {
                    detectTapGestures { offset ->
                        onValueChange(positionToIndex(offset.y))
                    }
                }
                .pointerInput(minIndex, maxIndex) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValueChange(positionToIndex(change.position.y))
                    }
                },
        ) {
            val trackX = size.width * 0.72f
            val trackTop = topPaddingPx
            val trackBottom = size.height - bottomPaddingPx
            val trackWidth = 2.dp.toPx()
            val majorStroke = 2.4.dp.toPx()
            val minorStroke = 1.4.dp.toPx()
            val trackColor = Color.White.copy(alpha = 0.22f)
            val tickColor = Color.White.copy(alpha = 0.78f)

            drawLine(
                color = trackColor,
                start = Offset(trackX, trackTop),
                end = Offset(trackX, trackBottom),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )

            if (selectableRange > 0) {
                val minorDivisions = 5
                for (index in minIndex until maxIndex) {
                    val baseFraction = (maxIndex - index).toFloat() / selectableRange.toFloat()
                    for (division in 1 until minorDivisions) {
                        val fraction = baseFraction - (division / minorDivisions.toFloat() / selectableRange.toFloat())
                        val y = trackTop + trackHeightPx * fraction
                        drawLine(
                            color = Color.White.copy(alpha = 0.34f),
                            start = Offset(trackX - 10.dp.toPx(), y),
                            end = Offset(trackX, y),
                            strokeWidth = minorStroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }

            for (index in minIndex..maxIndex) {
                val fractionFromTop = if (selectableRange == 0) 0.5f else {
                    (maxIndex - index).toFloat() / selectableRange.toFloat()
                }
                val y = trackTop + trackHeightPx * fractionFromTop
                val isCurrent = index == clampedIndex
                val isZero = index == 0
                val tickLength = when {
                    isCurrent -> 28.dp.toPx()
                    isZero -> 22.dp.toPx()
                    else -> 16.dp.toPx()
                }
                drawLine(
                    color = when {
                        isCurrent -> activeColor
                        isZero -> Color.White.copy(alpha = 0.96f)
                        else -> tickColor
                    },
                    start = Offset(trackX - tickLength, y),
                    end = Offset(trackX, y),
                    strokeWidth = if (isCurrent) 3.dp.toPx() else majorStroke,
                    cap = StrokeCap.Round,
                )
            }

            drawLine(
                color = activeColor.copy(alpha = 0.92f),
                start = Offset(trackX - 34.dp.toPx(), thumbY),
                end = Offset(trackX + 4.dp.toPx(), thumbY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 0.dp, y = thumbOffsetY),
            shape = RoundedCornerShape(999.dp),
            color = Color(0x7A1E2A35),
        ) {
            Text(
                text = formatExposureValue(currentIndex, stepEv),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun CameraTimerOptions(
    timerSec: Int,
    onTimerSecChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.End,
    ) {
        listOf(0, 3, 5, 10).forEach { sec ->
            Surface(
                modifier = Modifier
                    .width(54.dp)
                    .clickable { onTimerSecChange(sec) },
                shape = RoundedCornerShape(12.dp),
                color = if (timerSec == sec) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    Color(0x5A1E2A35)
                },
            ) {
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (timerSec == sec) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.24f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (sec == 0) "关" else "${sec}s",
                        color = if (timerSec == sec) MaterialTheme.colorScheme.primary else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

private fun formatExposureValue(
    index: Int,
    stepEv: Float,
): String {
    val ev = index * stepEv
    return if (ev > 0f) "+${"%.1f".format(ev)}" else "%.1f".format(ev)
}

@Composable
private fun TopSettingIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF0F172A),
        textAlign = TextAlign.Center,
    )
}

private fun bindUseCases(
    context: Context,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    analysisExecutor: ExecutorService,
    lens: CameraLens,
    flashMode: FlashMode,
    onAnalyze: (androidx.camera.core.ImageProxy) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onCameraReady: (androidx.camera.core.Camera) -> Unit,
) {
    val tag = "CamMate"
    val analysisSize = Size(512, 288)
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
        {
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flashMode.imageCaptureMode)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                analysisSize,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor) { imageProxy ->
                        onAnalyze(imageProxy)
                    }
                }

            val selector = if (lens == CameraLens.FRONT) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture,
                    imageAnalysis,
                )
                onImageCaptureReady(imageCapture)
                onCameraReady(camera)
            } catch (e: Exception) {
                Log.e(tag, "[camera.bind] 主摄像头绑定失败，尝试回退", e)
                if (lens == CameraLens.FRONT) {
                    try {
                        cameraProvider.unbindAll()
                        val fallbackCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis,
                        )
                        onImageCaptureReady(imageCapture)
                        onCameraReady(fallbackCamera)
                        Toast.makeText(context, "前置相机不可用，已切换后置", Toast.LENGTH_SHORT).show()
                    } catch (fallbackError: Exception) {
                        Log.e(tag, "[camera.bind] 前置回退后置失败", fallbackError)
                        Toast.makeText(context, "相机初始化失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "相机初始化失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    flashMode: FlashMode,
    lens: CameraLens,
    onSaved: (String?) -> Unit,
) {
    val tag = "CamMate"
    val capture = imageCapture ?: return
    capture.flashMode = flashMode.imageCaptureMode

    val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "CamMate_$fileName")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CamMate")
        }
    }

    val metadata = ImageCapture.Metadata().apply {
        // Make front-camera captures consistent with what users see in preview.
        isReversedHorizontal = lens == CameraLens.FRONT
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues,
    )
        .setMetadata(metadata)
        .build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri?.toString())
                Toast.makeText(context, "已保存到系统相册", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(tag, "[camera.capture] 拍照失败", exception)
                Toast.makeText(context, "拍照失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

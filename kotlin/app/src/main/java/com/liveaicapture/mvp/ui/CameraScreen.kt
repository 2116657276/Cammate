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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    openSettings: () -> Unit,
    openRetouch: () -> Unit,
    openFeedback: () -> Unit,
    openCommunity: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rollDegrees = rememberDeviceRollDegrees()

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (hasCameraPermission) {
            key(lens) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授权")
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xAA032A49),
                ) {
                    Text(
                        text = "${uiState.detectedScene.label} ${"%.0f".format(uiState.sceneConfidence * 100)}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color(0xFF7CE3FF),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = openCommunity,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x80000000), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.People,
                            contentDescription = "社区",
                            tint = Color.White,
                        )
                    }
                    IconButton(
                        onClick = openSettings,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0x80000000), CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "设置",
                            tint = Color.White,
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0x8A10151C),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TopSettingIconButton(Icons.Outlined.FlashOn, "闪光灯") { activeSheet = CameraSettingSheet.FLASH }
                    TopSettingIconButton(Icons.Outlined.ZoomIn, "焦距") { activeSheet = CameraSettingSheet.ZOOM }
                    TopSettingIconButton(Icons.Outlined.Exposure, "曝光") { activeSheet = CameraSettingSheet.EXPOSURE }
                    TopSettingIconButton(Icons.Outlined.Timer, "倒计时") { activeSheet = CameraSettingSheet.TIMER }
                    TopSettingIconButton(
                        Icons.Outlined.Cameraswitch,
                        if (lens == CameraLens.BACK) "切换前置" else "切换后置",
                    ) {
                        val nextLens = if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
                        lens = nextLens
                        if (nextLens == CameraLens.FRONT) {
                            flash = FlashMode.OFF
                            torchEnabled = false
                        }
                    }
                }
            }
        }

        if (uiState.analyzingTips) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xCC071B2E),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color(0xFF7CE3FF),
                        strokeWidth = 2.dp,
                    )
                    Text("AI思考中，请保持画面稳定", color = Color.White, fontSize = 13.sp)
                }
            }
        }

        if (countdown > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(CircleShape),
                color = Color(0xB3000000),
            ) {
                Text(
                    text = countdown.toString(),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.displayMedium,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF2FFFFFF)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("AI 建议", color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { aiPanelDetailed = !aiPanelDetailed }) {
                            Text(if (aiPanelDetailed) "简略" else "详情")
                        }
                    }
                    Text(
                        text = uiState.tipText,
                        color = Color(0xFF1E293B),
                        maxLines = if (aiPanelDetailed) 3 else 2,
                    )
                    if (uiState.moveHintText.isNotBlank()) {
                        Text(
                            text = uiState.moveHintText,
                            color = Color(0xFFB45309),
                            fontSize = 12.sp,
                        )
                    }
                    AnimatedVisibility(visible = aiPanelDetailed) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("状态：${uiState.statusText}", color = Color(0xFF64748B), fontSize = 12.sp)
                            Text(
                                text = "稳定度 ${"%.0f".format(uiState.stabilityScore * 100)}% · 倾斜 ${"%.1f".format(abs(rollDegrees))}°",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                            )
                            uiState.exposureSuggestion?.let {
                                Text("曝光建议 EV：$it", color = Color(0xFF64748B), fontSize = 12.sp)
                            }
                        }
                    }
                    if (uiState.analyzingTips) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptureMode.entries.forEach { mode ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = uiState.settings.captureMode == mode,
                        onClick = { viewModel.updateCaptureMode(mode) },
                        label = {
                            Text(
                                text = mode.label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xDDF8FAFC),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        TextButton(
                            enabled = !uiState.analyzingTips,
                            onClick = { viewModel.requestAiAnalyze() },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Tune, contentDescription = null)
                                Text(if (uiState.analyzingTips) "分析中" else "AI 分析")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (captureJob != null) return@Button
                            captureJob = scope.launch {
                                if (timerSec > 0) {
                                    for (left in timerSec downTo 1) {
                                        countdown = left
                                        delay(1000)
                                    }
                                }
                                capturePhoto(
                                    context = context,
                                    imageCapture = imageCapture,
                                    flashMode = flash,
                                    lens = lens,
                                ) { uriString ->
                                    viewModel.onPhotoCaptured(uriString)
                                }
                                countdown = 0
                            }.also { job ->
                                job.invokeOnCompletion {
                                    captureJob = null
                                    countdown = 0
                                }
                            }
                        },
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape),
                        shape = CircleShape,
                    ) {
                        Text(if (timerSec > 0) "${timerSec}s" else "拍照")
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            text = if (timerSec > 0) "倒计时 ${timerSec}s" else "轻触拍摄",
                            color = Color(0xFF64748B),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }

    activeSheet?.let { sheet ->
        ModalBottomSheet(
            onDismissRequest = { activeSheet = null },
            containerColor = Color(0xFFF9FAFB),
        ) {
            SheetTitle(
                when (sheet) {
                    CameraSettingSheet.FLASH -> "闪光灯"
                    CameraSettingSheet.ZOOM -> "焦距"
                    CameraSettingSheet.EXPOSURE -> "曝光"
                    CameraSettingSheet.TIMER -> "倒计时"
                },
            )

            when (sheet) {
                CameraSettingSheet.FLASH -> {
                    val torchSupported = hasFlashUnit && lens == CameraLens.BACK
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            FlashMode.entries.forEach { mode ->
                                val enabled = (lens == CameraLens.BACK && hasFlashUnit) || mode == FlashMode.OFF
                                FilterChip(
                                    selected = flash == mode,
                                    enabled = enabled,
                                    onClick = {
                                        if (enabled) {
                                            flash = mode
                                            if (mode != FlashMode.OFF) torchEnabled = false
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                        FilterChip(
                            selected = torchEnabled,
                            enabled = torchSupported,
                            onClick = {
                                if (torchSupported) {
                                    torchEnabled = !torchEnabled
                                    if (torchEnabled) flash = FlashMode.OFF
                                }
                            },
                            label = {
                                Text(
                                    if (!torchSupported) "补光不可用"
                                    else if (torchEnabled) "常亮补光：开"
                                    else "常亮补光：关",
                                )
                            },
                        )
                        if (lens == CameraLens.FRONT) {
                            Text("前置镜头不支持闪光与补光", color = Color(0xFF64748B), fontSize = 12.sp)
                        } else if (!hasFlashUnit) {
                            Text("当前后置镜头无闪光灯硬件", color = Color(0xFF64748B), fontSize = 12.sp)
                        } else {
                            Text("闪光模式与常亮补光互斥", color = Color(0xFF64748B), fontSize = 12.sp)
                        }
                    }
                }

                CameraSettingSheet.ZOOM -> {
                    val supportsHalf = minZoomRatio <= 0.5f + 0.01f
                    val supportsFive = maxZoomRatio >= 5f - 0.01f
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("当前 ${"%.1f".format(zoomRatio)}x")
                        Slider(
                            value = zoomRatio,
                            onValueChange = { zoomRatio = it },
                            valueRange = minZoomRatio..maxZoomRatio,
                            enabled = maxZoomRatio > minZoomRatio,
                            modifier = Modifier.fillMaxWidth(0.92f),
                        )
                        if (!supportsHalf || !supportsFive) {
                            Text(
                                text = "当前设备可用范围 ${"%.1f".format(minZoomRatio)}x ~ ${"%.1f".format(maxZoomRatio)}x",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                CameraSettingSheet.EXPOSURE -> {
                    val exposureSupported = maxExposureIndex > minExposureIndex
                    val currentEv = exposureIndex * exposureStepEv
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            if (exposureSupported) {
                                "当前 ${if (currentEv >= 0f) "+" else ""}${"%.1f".format(currentEv)} EV"
                            } else {
                                "设备不支持曝光补偿"
                            },
                        )
                        Slider(
                            value = exposureIndex.toFloat(),
                            onValueChange = { exposureIndex = it.toInt() },
                            valueRange = minExposureIndex.toFloat()..maxExposureIndex.toFloat(),
                            enabled = exposureSupported,
                            steps = (maxExposureIndex - minExposureIndex - 1).coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth(0.92f),
                        )
                    }
                }

                CameraSettingSheet.TIMER -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        listOf(0, 3, 5, 10).forEach { sec ->
                            FilterChip(
                                selected = timerSec == sec,
                                onClick = { timerSec = sec },
                                modifier = Modifier.padding(horizontal = 4.dp),
                                label = { Text(if (sec == 0) "关闭" else "${sec}s") },
                            )
                        }
                    }
                }

            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    if (uiState.showPostCaptureChoice && !uiState.lastPhotoUri.isNullOrBlank()) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissPostCaptureChoice() },
            containerColor = Color(0xFFF9FAFB),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "照片已保存，下一步怎么做？",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF0F172A),
                )
                Text(
                    text = "你可以直接继续原图，也可以先进入 AI 修图。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        viewModel.dismissPostCaptureChoice()
                        viewModel.continueWithOriginalPhoto()
                        openFeedback()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("继续原图")
                }
                Button(
                    onClick = {
                        viewModel.dismissPostCaptureChoice()
                        openRetouch()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("进入 AI 修图")
                }
                TextButton(
                    onClick = { viewModel.dismissPostCaptureChoice() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("返回拍摄")
                }
                Spacer(modifier = Modifier.padding(bottom = 24.dp))
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
private fun TopSettingIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .background(Color(0x5AFFFFFF), CircleShape),
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

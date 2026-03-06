package com.liveaicapture.mvp.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liveaicapture.mvp.data.CaptureMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    openSettings: () -> Unit,
    openRetouch: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
    var lens by rememberSaveable { mutableStateOf(CameraLens.BACK) }
    var flash by rememberSaveable { mutableStateOf(FlashMode.OFF) }
    var timerSec by rememberSaveable { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var captureJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF061327),
                        Color(0xFF0A1B33),
                        Color(0xFF12263F),
                    ),
                ),
            ),
    ) {
        if (hasCameraPermission) {
            key(lens, flash) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            bindUseCases(
                                context = ctx,
                                lifecycleOwner = lifecycleOwner,
                                previewView = this,
                                analysisExecutor = analysisExecutor,
                                lens = lens,
                                flashMode = flash,
                                onAnalyze = { imageProxy -> viewModel.onFrame(imageProxy) },
                                onImageCaptureReady = { capture -> imageCapture = capture },
                            )
                        }
                    },
                )
            }
            CameraOverlay(state = uiState.overlay)
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xAA032A49),
                ) {
                    Text(
                        text = "${uiState.detectedScene.label} · ${"%.0f".format(uiState.sceneConfidence * 100)}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color(0xFF7CE3FF),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                TextButton(onClick = openSettings) {
                    Text("设置", color = Color.White)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xA6142A45),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(uiState.tipText, color = Color.White, fontWeight = FontWeight.Medium)
                    uiState.exposureSuggestion?.let { ev ->
                        Text("曝光建议 EV: $ev", color = Color(0xFFC5E8FF), fontSize = 13.sp)
                    }
                    Text(
                        text = "模式: ${uiState.settings.captureMode.label} · ${uiState.statusText}",
                        color = Color(0xFF9BC4E2),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = "会话: 帧 ${uiState.sessionFrameCount} / 提示 ${uiState.sessionTipCount} / 照片 ${uiState.photoCount}",
                        color = Color(0xFF9BC4E2),
                        fontSize = 12.sp,
                    )
                }
            }

            if (uiState.settings.debugEnabled && uiState.debugRaw.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0x902B3E55))) {
                    Text(
                        text = uiState.debugRaw,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFFD7EEFF),
                        fontSize = 11.sp,
                    )
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

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xB010223A)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.settings.captureMode == mode,
                            onClick = { viewModel.updateCaptureMode(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = lens == CameraLens.BACK,
                        onClick = { lens = CameraLens.BACK },
                        label = { Text("后摄") },
                    )
                    FilterChip(
                        selected = lens == CameraLens.FRONT,
                        onClick = { lens = CameraLens.FRONT },
                        label = { Text("前摄") },
                    )
                    FlashMode.entries.forEach { mode ->
                        FilterChip(
                            selected = flash == mode,
                            onClick = { flash = mode },
                            label = { Text(mode.label) },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 3, 5).forEach { sec ->
                        FilterChip(
                            selected = timerSec == sec,
                            onClick = { timerSec = sec },
                            label = { Text(if (sec == 0) "无倒计时" else "${sec}s") },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { viewModel.requestAiAnalyze() }) {
                        Text(
                            text = if (uiState.analyzingTips) "分析中..." else "AI分析",
                            color = if (uiState.analyzingTips) Color(0xFF7CE3FF) else Color.White,
                        )
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
                                ) { uriString ->
                                    viewModel.onPhotoCaptured(uriString)
                                    if (!uriString.isNullOrBlank()) {
                                        openRetouch()
                                    }
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

                    Text(text = "", modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
    }
}

private enum class CameraLens { BACK, FRONT }

private enum class FlashMode(val label: String, val imageCaptureMode: Int) {
    OFF("闪光关", ImageCapture.FLASH_MODE_OFF),
    AUTO("闪光自动", ImageCapture.FLASH_MODE_AUTO),
    ON("闪光开", ImageCapture.FLASH_MODE_ON),
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
) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener(
        {
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(flashMode.imageCaptureMode)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
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
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageCapture,
                    imageAnalysis,
                )
                onImageCaptureReady(imageCapture)
            } catch (_: Exception) {
                if (lens == CameraLens.FRONT) {
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis,
                        )
                        onImageCaptureReady(imageCapture)
                        Toast.makeText(context, "前置相机不可用，已切换后置", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(context, "Camera 绑定失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Camera 绑定失败", Toast.LENGTH_SHORT).show()
                }
            }
        },
        ContextCompat.getMainExecutor(context),
    )
}

private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture?,
    onSaved: (String?) -> Unit,
) {
    val capture = imageCapture ?: return

    val fileName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "LiveAICapture_$fileName")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LiveAICapture")
        }
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues,
    ).build()

    capture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onSaved(outputFileResults.savedUri?.toString())
                Toast.makeText(context, "已保存到系统相册", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(context, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        },
    )
}

package com.scanner.lite

import android.content.Intent
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

// 主界面：相机画面 + 上面叠加各种按钮
@Composable
fun ScannerScreen() {
    val context = LocalContext.current
    var flashOn by remember { mutableStateOf(false) }      // 闪光灯是否开启
    var isScanMode by remember { mutableStateOf(true) }    // true=扫码，false=OCR
    var scanning by remember { mutableStateOf(true) }      // 是否正在等待识别结果
    var recognizing by remember { mutableStateOf(false) }  // 是否正在识别图片中
    var camera by remember { mutableStateOf<Camera?>(null) } // 相机对象，用来控制手电筒

    // 拍照用例
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // 相册选图（系统照片选择器，不需要权限）
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            recognizing = true
            recognizeFromUri(context, uri, isScanMode) { recognizing = false }
        }
    }

    // 相机就绪或 flashOn 变化时，真正开关手电筒
    LaunchedEffect(camera, flashOn) {
        camera?.cameraControl?.enableTorch(flashOn)
    }

    // 回到页面时重新开始扫码；离开页面时手电筒会自动熄灭，图标同步变回"关"
    LifecycleResumeEffect(Unit) {
        scanning = true
        onPauseOrDispose { flashOn = false }
    }
    // 只有"直接复制"模式不跳页面，才需要隔一会儿自动恢复扫码
    LaunchedEffect(scanning) {
        if (!scanning && SettingsStore.copyDirect(context)) {
            delay(2500.milliseconds)
            scanning = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 最底层：相机画面
        CameraPreview(
            imageCapture = imageCapture,
            scanEnabled = isScanMode && scanning && !recognizing,
            onBarcode = { text ->
                if (scanning) {
                    scanning = false // 防止重复触发
                    openResult(context, text, isOcr = false)
                }
            },
            onCameraReady = { camera = it }
        )

        // 顶部：左上设置，右上闪光灯
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RoundIconButton(Icons.Filled.Settings, "设置") {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
            RoundIconButton(
                if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                "闪光灯"
            ) {
                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                if (hasFlash) {
                    flashOn = !flashOn
                } else {
                    Toast.makeText(context, "此设备没有闪光灯", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 识别中：屏幕中间转圈
        if (recognizing) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
        }

        // 底部：切换按钮在上，历史 / 拍照 / 相册在下
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ModeSwitch(isScanMode) { isScanMode = it }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    RoundIconButton(Icons.Filled.History, "历史") {
                        context.startActivity(Intent(context, HistoryActivity::class.java))
                    }
                }
                CaptureButton {
                    if (!recognizing) {
                        recognizing = true
                        takePhoto(context, imageCapture, isScanMode) { recognizing = false }
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    RoundIconButton(Icons.Filled.PhotoLibrary, "相册") {
                        if (!recognizing) {
                            pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    }
                }
            }
        }
    }
}

// 相机画面 + 实时分析 + 拍照
@Composable
fun CameraPreview(
    imageCapture: ImageCapture,
    scanEnabled: Boolean,
    onBarcode: (String) -> Unit,
    onCameraReady: (Camera) -> Unit   // 新增：相机绑定成功后，把相机对象交出去
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // 让相机里的回调始终拿到最新的值
    val currentEnabled by rememberUpdatedState(scanEnabled)
    val currentOnBarcode by rememberUpdatedState(onBarcode)

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                // 分析分辨率提高到 1080p，小码、远处的码识别率更高
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(
                    Executors.newSingleThreadExecutor(),
                    BarcodeAnalyzer(
                        isEnabled = { currentEnabled },
                        onResult = { currentOnBarcode(it) }
                    )
                )

                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                    imageCapture
                )
                onCameraReady(boundCamera)
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}
package com.scanner.lite

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Patterns
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // View 声明
    private lateinit var previewView: PreviewView
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnGallery: MaterialButton
    private lateinit var btnHistory: MaterialButton
    private var btnSettings: MaterialButton? = null
    private lateinit var btnCapture: FloatingActionButton
    private lateinit var btnTabScan: TextView
    private lateinit var btnTabOcr: TextView
    private lateinit var scanLine: View
    private lateinit var previewContainer: View

    // CameraX 与 线程池
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    // 状态与配置标记
    private var isFlashOn = false
    @Volatile // 确保跨线程可见性
    private var isScanningEnabled = true
    private var isOcrMode = false // false: 扫码, true: OCR
    private var scanAnimator: ObjectAnimator? = null

    private lateinit var prefs: SharedPreferences
    private var isScanDialogEnabled = true
    private var isOcrUrlEnabled = true
    private var isScanUrlEnabled = true // 扫码网址检测开关

    // 全局持有弹窗引用，防止 WindowLeaked 内存泄漏
    private var currentDialog: BottomSheetDialog? = null

    // 助手类
    private val ocrManager by lazy { OcrManager() }
    private val translateManager by lazy { TranslateManager() }

    // 提示音与 Barcode 扫描器
    private lateinit var soundPool: SoundPool
    private var soundId: Int = 0
    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            // 明确只允许最常用的几种一维码和二维码，彻底根治“误识别”
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_EAN_13
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    // 相册选择器
    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageFromGallery(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 开启全面屏沉浸（兼容 Android 13 及以下）
        enableEdgeToEdge()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 确保系统状态栏和导航栏全透明延伸
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        initSettings()
        initViews()
        initSoundPool()
        initListeners()

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
            startScanAnimation()
            handleIntent(intent)
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initSettings() {
        prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        isScanDialogEnabled = prefs.getBoolean("key_scan_dialog", true)
        isOcrUrlEnabled = prefs.getBoolean("key_ocr_url", true)
        isScanUrlEnabled = prefs.getBoolean("key_scan_url", true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.scanner.lite.ACTION_START_SCAN") {
            if (isOcrMode) {
                switchMode(toOcrMode = false)
            }
        }
    }

    private fun initViews() {
        btnFlash = findViewById(R.id.btnFlash)
        btnHistory = findViewById(R.id.btnHistory)
        btnGallery = findViewById(R.id.btnGallery)
        btnSettings = findViewById(R.id.btnSettings)
        btnCapture = findViewById(R.id.btnCapture)

        previewView = findViewById(R.id.previewView)

        btnTabScan = findViewById(R.id.btnTabScan)
        btnTabOcr = findViewById(R.id.btnTabOcr)
        scanLine = findViewById(R.id.scanLine)
        previewContainer = findViewById(R.id.previewContainer)

        updateTabUiState(isOcr = false, animate = false)
    }

    private fun initListeners() {
        btnHistory.setOnClickListener {
            startActivity(Intent(this, ScanHistoryActivity::class.java))
        }

        btnSettings?.setOnClickListener {
            showSettingsBottomSheet()
        }

        setupFlashButton()
        setupZoomGesture()

        btnGallery.setOnClickListener {
            selectImageLauncher.launch("image/*")
        }

        btnCapture.setOnClickListener {
            takePhotoAndRecognize()
        }

        btnTabScan.setOnClickListener {
            if (isOcrMode) switchMode(toOcrMode = false)
        }

        btnTabOcr.setOnClickListener {
            if (!isOcrMode) switchMode(toOcrMode = true)
        }
    }

    private fun setupZoomGesture() {
        val scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCamera = camera ?: return false
                    val currentZoomRatio = activeCamera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor

                    activeCamera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                    return true
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()

                // 点击屏幕时，触发相机在该点进行自动对焦
                camera?.let { cam ->
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    cam.cameraControl.startFocusAndMetering(action)
                }
            }
            true
        }
    }

    private fun switchMode(toOcrMode: Boolean) {
        this.isOcrMode = toOcrMode
        updateTabUiState(isOcr = toOcrMode, animate = true)

        if (toOcrMode) {
            stopScanAnimation()
            isScanningEnabled = false
        } else {
            resumeScanAnimation()
            isScanningEnabled = true
        }
    }

    private fun updateTabUiState(isOcr: Boolean, animate: Boolean) {
        val activeColor = ContextCompat.getColor(this, android.R.color.white)
        val inactiveColor = ContextCompat.getColor(this, android.R.color.darker_gray)

        val scanColor = if (isOcr) inactiveColor else activeColor
        val ocrColor = if (isOcr) activeColor else inactiveColor

        btnTabScan.setTextColor(scanColor)
        btnTabScan.typeface = if (isOcr) Typeface.DEFAULT else Typeface.DEFAULT_BOLD

        btnTabOcr.setTextColor(ocrColor)
        btnTabOcr.typeface = if (isOcr) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        val duration = if (animate) 200L else 0L

        if (isOcr) {
            btnCapture.visibility = View.VISIBLE
            btnCapture.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(duration)
                .start()
        } else {
            btnCapture.animate()
                .alpha(0.0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(duration)
                .withEndAction { btnCapture.visibility = View.GONE }
                .start()
        }
    }

    private fun setupFlashButton() {
        btnFlash.setIconResource(R.drawable.ic_flash_off)
        btnFlash.setOnClickListener {
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    isFlashOn = !isFlashOn
                    cam.cameraControl.enableTorch(isFlashOn)
                    btnFlash.setIconResource(
                        if (isFlashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
                    )
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImageProxy(imageProxy)
                    }
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer, imageCapture
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && !isOcrMode && isScanningEnabled) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // 第一步：先用 ML Kit 进行识别
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && isScanningEnabled && !isOcrMode) {
                        isScanningEnabled = false
                        barcodes.firstOrNull()?.rawValue?.let { onBarcodeDetected(it) }
                        imageProxy.close()
                    } else {
                        // 如果 ML Kit 未匹配到目标，转交 ZXing 兜底
                        decodeWithZXing(imageProxy)
                    }
                }
                .addOnFailureListener {
                    decodeWithZXing(imageProxy)
                }
        } else {
            imageProxy.close()
        }
    }

    private fun decodeWithZXing(imageProxy: ImageProxy) {
        if (!isScanningEnabled || isOcrMode) {
            imageProxy.close()
            return
        }

        cameraExecutor.execute {
            try {
                val plane = imageProxy.planes[0]
                val buffer = plane.buffer
                val width = imageProxy.width
                val height = imageProxy.height
                val rowStride = plane.rowStride

                // 剥离 CameraX 的行对齐填充 (Row Stride Padding)，防止图像发生横向切变与错位
                val cleanedData = ByteArray(width * height)
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(cleanedData, row * width, width)
                }

                val source = PlanarYUVLuminanceSource(
                    cleanedData, width, height, 0, 0, width, height, false
                )
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val reader = MultiFormatReader()
                val result = reader.decodeWithState(binaryBitmap)

                if (result != null && isScanningEnabled && !isOcrMode) {
                    isScanningEnabled = false
                    runOnUiThread {
                        onBarcodeDetected(result.text)
                    }
                }
            } catch (_: Exception) {
                // ZXing 未捕获条码抛出异常属正常流程
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun takePhotoAndRecognize() {
        val capture = imageCapture ?: return
        Toast.makeText(this, "正在识别中...", Toast.LENGTH_SHORT).show()

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                @OptIn(ExperimentalGetImage::class)
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    ocrManager.processImageProxy(
                        imageProxy = imageProxy,
                        onSuccess = { rawText ->
                            if (rawText.isNotBlank()) {
                                showOcrResultBottomSheet(rawText)
                            } else {
                                Toast.makeText(this@MainActivity, "未检测到文本", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onFailure = {
                            Toast.makeText(this@MainActivity, "文本识别失败", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processImageFromGallery(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            if (isOcrMode) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                }

                ocrManager.processBitmap(
                    bitmap = bitmap,
                    onSuccess = { text ->
                        if (text.isNotBlank()) {
                            showOcrResultBottomSheet(text)
                        } else {
                            Toast.makeText(this, "未检测到文本", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFailure = {
                        Toast.makeText(this, "识别失败", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            isScanningEnabled = false
                            barcodes.firstOrNull()?.rawValue?.let { onBarcodeDetected(it) }
                        } else {
                            Toast.makeText(this, "未识别到有效的二维码", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "图片解析失败", Toast.LENGTH_SHORT).show()
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBarcodeDetected(result: String) {
        runOnUiThread { stopScanAnimation() }

        playBeepSound()
        window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)

        lifecycleScope.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(applicationContext).scanDao()
            dao.insert(ScanRecord(content = result))
        }

        runOnUiThread {
            val matcher = Patterns.WEB_URL.matcher(result)
            val isUrl = matcher.find()

            if (isScanUrlEnabled && isUrl) {
                val foundUrl = matcher.group() ?: result
                val urlToOpen = if (!foundUrl.startsWith("http://") && !foundUrl.startsWith("https://")) {
                    "https://$foundUrl"
                } else {
                    foundUrl
                }
                showUrlBottomSheet(urlToOpen)
            } else if (isScanDialogEnabled) {
                showResultBottomSheet(result)
            } else {
                copyToClipboard(result)
                Toast.makeText(this, "已自动复制结果", Toast.LENGTH_SHORT).show()
                previewView.postDelayed({
                    if (!isOcrMode) {
                        isScanningEnabled = true
                        resumeScanAnimation()
                    }
                }, 1500)
            }
        }
    }

    private fun showResultBottomSheet(text: String) {
        currentDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.dialog_result_bottom_sheet, rootView, false)

        val tvResult = view.findViewById<TextView>(R.id.tvResultContent)
        val btnCopy = view.findViewById<Button>(R.id.btnCopy)

        tvResult.text = text

        btnCopy.setOnClickListener {
            copyToClipboard(text)
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!isOcrMode) {
                isScanningEnabled = true
                resumeScanAnimation()
            }
            currentDialog = null
        }

        dialog.setContentView(view)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showUrlBottomSheet(urlToOpen: String) {
        currentDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.dialog_url_prompt, rootView, false)

        val tvUrlContent = view.findViewById<TextView>(R.id.tvUrlContent)
        val btnOpenUrl = view.findViewById<Button>(R.id.btnOpenUrl)
        val btnCancelUrl = view.findViewById<Button>(R.id.btnCancelUrl)

        tvUrlContent.text = urlToOpen

        btnOpenUrl.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, urlToOpen.toUri())
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开该链接", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        btnCancelUrl.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (!isOcrMode) {
                isScanningEnabled = true
                resumeScanAnimation()
            }
            currentDialog = null
        }

        dialog.setContentView(view)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showOcrResultBottomSheet(rawText: String) {
        currentDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.dialog_ocr_result, rootView, false)

        val tvOcrOriginal = view.findViewById<TextView>(R.id.tvOcrOriginal)
        val tvOcrTranslated = view.findViewById<TextView>(R.id.tvOcrTranslated)
        val layoutTranslation = view.findViewById<View>(R.id.layoutTranslation)
        val btnCopyOriginal = view.findViewById<Button>(R.id.btnCopyOriginal)
        val btnStartTranslate = view.findViewById<Button>(R.id.btnStartTranslate)

        tvOcrOriginal.text = rawText

        if (isOcrUrlEnabled) {
            Linkify.addLinks(tvOcrOriginal, Linkify.WEB_URLS)
            tvOcrOriginal.movementMethod = LinkMovementMethod.getInstance()
        }

        btnCopyOriginal.setOnClickListener {
            copyToClipboard(rawText)
        }

        btnStartTranslate.setOnClickListener {
            btnStartTranslate.isEnabled = false
            btnStartTranslate.text = "翻译中..."

            translateManager.translate(
                text = rawText,
                onSuccess = { translated ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        layoutTranslation.visibility = View.VISIBLE
                        tvOcrTranslated.text = translated.ifBlank { "（未能翻译该文本）" }
                        btnStartTranslate.text = "已翻译"
                    }
                },
                onFailure = {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread

                        btnStartTranslate.isEnabled = true
                        btnStartTranslate.text = "重试翻译"
                        Toast.makeText(this@MainActivity, "翻译引擎加载失败", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        dialog.setOnDismissListener {
            currentDialog = null
        }

        dialog.setContentView(view)

        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            bottomSheet.setBackgroundResource(android.R.color.transparent)
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = false
        }

        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showSettingsBottomSheet() {
        currentDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.dialog_settings, rootView, false)

        val switchScanDialog = view.findViewById<MaterialSwitch>(R.id.switchScanDialog)
        val switchOcrUrl = view.findViewById<MaterialSwitch>(R.id.switchOcrUrl)
        val switchScanUrl = view.findViewById<MaterialSwitch>(R.id.switchScanUrl)

        switchScanDialog?.isChecked = isScanDialogEnabled
        switchOcrUrl?.isChecked = isOcrUrlEnabled
        switchScanUrl?.isChecked = isScanUrlEnabled

        switchScanDialog?.setOnCheckedChangeListener { _, isChecked ->
            isScanDialogEnabled = isChecked
            prefs.edit { putBoolean("key_scan_dialog", isChecked) }
        }

        switchOcrUrl?.setOnCheckedChangeListener { _, isChecked ->
            isOcrUrlEnabled = isChecked
            prefs.edit { putBoolean("key_ocr_url", isChecked) }
        }

        switchScanUrl?.setOnCheckedChangeListener { _, isChecked ->
            isScanUrlEnabled = isChecked
            prefs.edit { putBoolean("key_scan_url", isChecked) }
        }

        dialog.setOnDismissListener {
            currentDialog = null
        }

        dialog.setContentView(view)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)
        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ScanResult", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
    }

    private fun startScanAnimation() {
        if (isOcrMode) return
        previewContainer.post {
            val height = previewContainer.height.toFloat()
            if (height <= 0) return@post

            scanLine.visibility = View.VISIBLE
            scanAnimator?.cancel()

            scanAnimator = ObjectAnimator.ofFloat(scanLine, "translationY", 0f, height - scanLine.height).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopScanAnimation() {
        scanAnimator?.pause()
        scanLine.visibility = View.INVISIBLE
    }

    private fun resumeScanAnimation() {
        if (isOcrMode) return
        scanLine.visibility = View.VISIBLE
        if (scanAnimator?.isPaused == true) {
            scanAnimator?.resume()
        } else {
            startScanAnimation()
        }
    }

    private fun initSoundPool() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(attributes)
            .build()

        soundId = soundPool.load(this, R.raw.beep, 1)
    }

    private fun playBeepSound() {
        if (soundId != 0) {
            soundPool.play(soundId, 1.0f, 1.0f, 0, 0, 1.0f)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
                startScanAnimation()
                handleIntent(intent)
            } else {
                Toast.makeText(this, "未授予摄像头权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isScanningEnabled && !isOcrMode && allPermissionsGranted()) {
            resumeScanAnimation()
        }
    }

    override fun onPause() {
        super.onPause()
        stopScanAnimation()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentDialog?.dismiss()
        scanAnimator?.cancel()
        cameraExecutor.shutdown()
        soundPool.release()
        barcodeScanner.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
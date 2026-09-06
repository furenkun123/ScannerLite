package com.scanner.lite

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Patterns
import android.view.HapticFeedbackConstants
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
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
    private var isScanningEnabled = true
    private var isOcrMode = false // false: 扫码, true: OCR
    private var scanAnimator: ObjectAnimator? = null

    private lateinit var prefs: SharedPreferences
    private var isScanDialogEnabled = true
    private var isOcrUrlEnabled = true
    private var isScanUrlEnabled = true // 新增：扫码网址检测开关

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
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
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
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
        isScanUrlEnabled = prefs.getBoolean("key_scan_url", true) // 初始化扫码网址检测设置
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
            view.performClick()
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

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty() && isScanningEnabled && !isOcrMode) {
                        barcodes.firstOrNull()?.rawValue?.let { result ->
                            onBarcodeDetected(result)
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
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
                val bitmap =
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))

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
        isScanningEnabled = false
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

            // 只有开启了扫码网址检测开关，并且解析出的是网址时，才展示网址对话框
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

        // 1. 开启 OCR 文本中的网址高亮与直接点击支持（在识别全文里可直接点击网址）
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
                        layoutTranslation.visibility = View.VISIBLE
                        tvOcrTranslated.text = translated.ifBlank { "（未能翻译该文本）" }
                        btnStartTranslate.text = "已翻译"
                    }
                },
                onFailure = {
                    runOnUiThread {
                        btnStartTranslate.isEnabled = true
                        btnStartTranslate.text = "重试翻译"
                        Toast.makeText(this, "翻译引擎加载失败", Toast.LENGTH_SHORT).show()
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

        // 2. OCR 文本网址识别提示框逻辑
        if (isOcrUrlEnabled) {
            checkAndPromptUrl(rawText)
        }
    }

    private fun showSettingsBottomSheet() {
        currentDialog?.dismiss()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog

        val rootView = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.dialog_settings, rootView, false)

        val switchScanDialog = view.findViewById<MaterialSwitch>(R.id.switchScanDialog)
        val switchOcrUrl = view.findViewById<MaterialSwitch>(R.id.switchOcrUrl)
        val switchScanUrl = view.findViewById<MaterialSwitch>(R.id.switchScanUrl) // 获取扫码网址开关控件

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

    private fun checkAndPromptUrl(text: String) {
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return

        val rawUrl = matcher.group() ?: return
        val urlToOpen = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        // 使用现有的 dialog_url_prompt 布局从底部弹出
        val dialog = BottomSheetDialog(this)
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
                Toast.makeText(this, "无法打开该链接，未找到合适应用", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        btnCancelUrl.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(view)

        // 背景透明化处理（适配圆角 Layout）
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
package com.scanner.lite

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

// 分析相机的每一帧画面，找出条码/二维码
class BarcodeAnalyzer(
    private val isEnabled: () -> Boolean,      // 当前是否需要识别
    private val onResult: (String) -> Unit     // 识别到内容后的回调
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS) // 支持所有码制
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || !isEnabled()) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val text = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                if (text != null) onResult(text)
            }
            .addOnCompleteListener { imageProxy.close() } // 一定要关闭，否则不会收到下一帧
    }
}
package com.scanner.lite

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

// 中文文字识别器（同时能识别英文和数字）
private val textRecognizer =
    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

// 条码识别器（支持所有码制）
private val barcodeScanner = BarcodeScanning.getClient(
    BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        .build()
)

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

// 识别一张图片：扫码模式找条码，OCR 模式识字，成功后跳转到结果页
// 无论成功失败，都会调用 onFinish，用来关闭"识别中"状态
fun recognizeImage(
    context: Context,
    image: InputImage,
    isScanMode: Boolean,
    onFinish: () -> Unit
) {
    if (isScanMode) {
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                onFinish()
                val text = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                if (text != null) {
                    openResult(context, text, isOcr = false)
                } else {
                    toast(context, "没有识别到条码或二维码")
                }
            }
            .addOnFailureListener {
                onFinish()
                toast(context, "识别失败，请重试")
            }
    } else {
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                onFinish()
                if (result.text.isBlank()) {
                    toast(context, "没有识别到文字")
                } else {
                    openResult(context, result.text, isOcr = true)
                }
            }
            .addOnFailureListener {
                onFinish()
                toast(context, "识别失败，请重试")
            }
    }
}

// 拍一张照片再识别
fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    isScanMode: Boolean,
    onFinish: () -> Unit
) {
    imageCapture.takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                recognizeImage(context, InputImage.fromBitmap(bitmap, rotation), isScanMode, onFinish)
            }

            override fun onError(exception: ImageCaptureException) {
                onFinish()
                toast(context, "拍照失败，请重试")
            }
        }
    )
}

// 识别相册里选中的图片
fun recognizeFromUri(
    context: Context,
    uri: Uri,
    isScanMode: Boolean,
    onFinish: () -> Unit
) {
    val image = try {
        InputImage.fromFilePath(context, uri) // 会自动处理照片的旋转信息
    } catch (_: Exception) {
        onFinish()
        toast(context, "图片读取失败")
        return
    }
    recognizeImage(context, image, isScanMode, onFinish)
}
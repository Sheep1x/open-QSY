package com.openqsy.glasses.scan

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把相机帧交给 ML Kit 条码识别，捞出第一个 http(s) 链接。
 *
 * 只认二维码（[Barcode.FORMAT_QR_CODE]）：限定格式能让 ML Kit 少走几条检测分支，
 * 在只有小核的 Go 设备上明显更快。
 *
 * 并发控制用 [AtomicBoolean] 而不是队列：识别比取帧慢，一旦排队，
 * 用户手移开了还在处理老画面，反而更容易超时。宁可丢帧。
 */
class QrCodeScanner(
    private val onUrl: (String) -> Unit,
    private val onFrame: () -> Unit = {},
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    private val busy = AtomicBoolean(false)

    /** 已扫到目标 / 页面已离开：不再往外报，也不再统计帧率。 */
    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        if (stopped || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val media = image.image
        if (media == null) {
            busy.set(false)
            image.close()
            return
        }
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                if (stopped) return@addOnSuccessListener
                for (code in codes) {
                    val raw = code.rawValue?.trim().orEmpty()
                    if (isWebUrl(raw)) {
                        // 拿到就立刻停：后面几帧没必要再识别
                        stopped = true
                        onUrl(raw)
                        break
                    }
                }
            }
            .addOnFailureListener { e -> Log.w(TAG, "条码识别失败", e) }
            .addOnCompleteListener {
                busy.set(false)
                if (!stopped) onFrame()
                image.close()
            }
    }

    private fun isWebUrl(s: String): Boolean =
        s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    companion object {
        private const val TAG = "QrCodeScanner"

        /**
         * 从一张图片文件里解出二维码中的链接。
         *
         * 给真机验收用：把「举着眼镜对准屏幕上的二维码」换成「读一张已经存好的图」，
         * 于是相机取景那一段之外的所有环节（ML Kit 解码、取链接、下载、编译、热替换）
         * 都能在 adb 上跑一遍。解码器和 [QrCodeScanner] 用的是同一个。
         */
        suspend fun decodeFile(context: Context, path: String): String? =
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.isFile) throw IllegalStateException("图片不存在：$path")
                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                )
                try {
                    val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                    val codes = Tasks.await(scanner.process(image))
                    for (code in codes) {
                        val raw = code.rawValue?.trim().orEmpty()
                        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                            return@withContext raw
                        }
                    }
                    null
                } finally {
                    runCatching { scanner.close() }
                }
            }
    }
}

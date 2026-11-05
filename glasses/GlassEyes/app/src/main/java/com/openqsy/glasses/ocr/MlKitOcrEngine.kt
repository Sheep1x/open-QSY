package com.openqsy.glasses.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit 中文文字识别（**模型内置于 APK，纯离线，不需要网络，不需要 Play 服务**）。
 *
 * 依赖：`com.google.mlkit:text-recognition-chinese`（bundled 版本，非 play-services 版本）。
 * APK 体积 +25~35 MB 是唯一代价。
 *
 * 若真机上报 `MlKitException: Failed to initialize detector`（个别 AOSP/Go 机型缺少
 * native 依赖），改用 PP-OCR(ONNX Runtime) 方案，接口不变，只需换一个 [OcrEngine] 实现，
 * 详见 docs/02-技术方案.md「OCR 选型」。
 */
class MlKitOcrEngine : OcrEngine {

    override val name: String = "ML Kit 中文(bundled)"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(
        image: ImageProxy,
        visibleAspect: Float,
    ): OcrOutcome = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        var keep: Bitmap? = null
        var tPrep = t0
        try {
            val rotation = image.imageInfo.rotationDegrees
            val input: InputImage
            val frameH: Float

            if (visibleAspect <= 0f) {
                // 整帧：直接把相机缓冲交给 ML Kit，零拷贝。
                // 缓冲必须活到识别结束，所以这里不能提前 close。
                val media = image.image ?: return@withContext OcrOutcome("", 0, "相机帧为空")
                frameH = media.height.toFloat().coerceAtLeast(1f)
                input = InputImage.fromMediaImage(media, rotation)
            } else {
                // 只认取景框里那一块。整帧宽高比和取景框不一样，
                // 屏幕上看到的是「整宽 × 纵向中间一段」，这里裁出同一块再识别，
                // 否则框外的相邻题目也会被认进来，答案就会跑到别的题上去。
                val full = runCatching { image.toBitmap() }.getOrNull()
                    ?: return@withContext OcrOutcome(
                        text = "",
                        elapsedMs = SystemClock.elapsedRealtime() - t0,
                        error = "取帧失败",
                    )
                // 相机缓冲到这里就用完了，立刻还回去。
                // ML Kit 每次要 400~800 ms，若等它跑完才 close，这块 buffer 就一直占着，
                // 相机流水线跟着停——用户看到的就是「每拍一张卡一下」。
                // 后面的识别只对着内存里的位图做，与相机无关。
                runCatching { image.close() }

                val upright = if (rotation == 0) full else {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
                        .also { if (it !== full) full.recycle() }
                }
                val shot = cropToViewfinder(upright, visibleAspect)
                if (shot !== upright) upright.recycle()
                // 这张要等识别结束才能回收
                keep = shot
                frameH = shot.height.toFloat().coerceAtLeast(1f)
                input = InputImage.fromBitmap(shot, 0)
            }

            tPrep = SystemClock.elapsedRealtime()
            val result = Tasks.await(recognizer.process(input))
            val ms = SystemClock.elapsedRealtime() - t0
            Log.d(TAG, "timing: prep=${tPrep - t0}ms ocr=${SystemClock.elapsedRealtime() - tPrep}ms")
            toOutcome(result, frameH, ms)
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit OCR 失败", t)
            OcrOutcome(
                text = "",
                elapsedMs = SystemClock.elapsedRealtime() - t0,
                error = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            keep?.recycle()
            // 必须释放，否则下一次取帧会因缓冲区耗尽而阻塞（重复 close 是幂等的）。
            // 这也是这一帧唯一的出口：不存文件、不上传，只留下文字。
            runCatching { image.close() }
        }
    }

    /**
     * 验收口子：直接对一张**图片文件解出来的位图**跑「裁剪 → 识别 → 分行」。
     *
     * 相机扫码/识别那一步没法远程触发，没有这个入口就只能靠人举着眼镜对着纸去试。
     * 传入的 [src] 由调用方负责回收，这里只回收自己造的中间位图。
     */
    suspend fun recognizeBitmap(
        src: Bitmap,
        visibleAspect: Float,
        rotation: Int = 0,
    ): OcrOutcome = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        var mid: Bitmap? = null
        var cut: Bitmap? = null
        try {
            val upright = if (rotation == 0) src else {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    .also { if (it !== src) mid = it }
            }
            val shot = cropToViewfinder(upright, visibleAspect)
                .also { if (it !== upright) cut = it }
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(shot, 0)))
            toOutcome(result, shot.height.toFloat(), SystemClock.elapsedRealtime() - t0)
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit OCR 失败(bitmap)", t)
            OcrOutcome(
                text = "",
                elapsedMs = SystemClock.elapsedRealtime() - t0,
                error = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            cut?.recycle()
            mid?.recycle()
        }
    }

    private fun toOutcome(result: Text, frameH: Float, ms: Long): OcrOutcome {
        // 把每一行的纵向位置带出去：一屏里常常有好几道题，
        // 只有知道"哪一行在画面正中"，才能把答案对到用户框住的那道题上。
        val lines = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { ln ->
                val box = ln.boundingBox ?: return@mapNotNull null
                val t = ln.text.trim()
                if (t.isEmpty()) null
                else OcrLine(t, (box.centerY() / frameH).coerceIn(0f, 1f))
            }
        return OcrOutcome(text = result.text, elapsedMs = ms, lines = lines)
    }

    /**
     * 把已经转正的一帧裁成「取景框里能看到的画面」。
     *
     * 取景框是 480×220 的横向框，相机帧是竖构图，`FILL_CENTER` 下的缩放系数由宽度定死，
     * 所以屏幕上露出来的是**整宽、纵向居中、约 1/3 高**的一条。
     * 这里用同一个比例去裁，保证「看到的」= 「识别的」。
     *
     * 返回的位图若与入参是同一个对象，调用方**不要**回收它。
     */
    private fun cropToViewfinder(upright: Bitmap, boxAspect: Float): Bitmap {
        if (boxAspect <= 0f) return upright
        val frameAspect = upright.height.toFloat() / upright.width.toFloat()
        val visibleFraction = (1f / boxAspect) / frameAspect
        val cropH = (upright.height * visibleFraction).toInt()
            .coerceIn(64, upright.height)
        val top = ((upright.height - cropH) / 2).coerceAtLeast(0)

        Log.d(
            TAG,
            "crop: upright=${upright.width}x${upright.height} " +
                "boxAspect=$boxAspect frac=$visibleFraction cropH=$cropH top=$top",
        )

        return if (cropH >= upright.height) {
            upright
        } else {
            Bitmap.createBitmap(upright, 0, top, upright.width, cropH)
        }
    }

    private companion object {
        const val TAG = "MlKitOcr"
    }
}

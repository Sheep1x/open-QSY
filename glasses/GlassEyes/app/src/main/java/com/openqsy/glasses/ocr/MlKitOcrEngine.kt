package com.openqsy.glasses.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
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
import java.nio.ByteBuffer

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
        // 交给界面的「这次认的是哪一块」。所有权转移给调用方，这里不回收。
        var frame: Bitmap? = null
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
                //
                // 走 NV21 而不是 Bitmap：`ImageProxy.toBitmap()` 会把**整帧**做一遍
                // YUV→ARGB（1.5 MP 要 130~150 ms，实测 setCropRect 并不影响它的开销），
                // 而这里只把取景框那一条的 Y/UV 拷出来（0.37 MP，20~40 ms），转换与旋转
                // 都交给 ML Kit 自己做——省掉整整一步，也省掉一张 Bitmap 的分配。
                val rect = viewfinderRect(image, visibleAspect)
                val turned = rotation == 90 || rotation == 270
                // 交给 ML Kit 的是「未转正」的图 + rotation，它给出的坐标是转正之后的
                val frameH = (if (turned) rect.width() else rect.height()).toFloat().coerceAtLeast(1f)
                val cropped = runCatching { cropToNv21(image, rect) }.getOrNull()
                // 相机缓冲到这里就用完了，立刻还回去。
                // ML Kit 每次要几百毫秒，若等它跑完才 close，这块 buffer 就一直占着，
                // 相机流水线跟着停——用户看到的就是「每拍一张卡一下」。
                runCatching { image.close() }
                if (cropped == null) {
                    return@withContext OcrOutcome(
                        text = "",
                        elapsedMs = SystemClock.elapsedRealtime() - t0,
                        error = "取帧失败",
                    )
                }
                input = InputImage.fromByteBuffer(
                    cropped.nv21,
                    rect.width(),
                    rect.height(),
                    rotation,
                    InputImage.IMAGE_FORMAT_NV21,
                )
                tPrep = SystemClock.elapsedRealtime()
                // 顺手把这块画面转成位图交给界面：用户要能看到「刚才认的是哪一块」。
                // 复用已经拷出来的 Y 平面（亮度），是一次内存里的灰度转换，不落盘。
                frame = runCatching { yToBitmap(cropped.y, cropped.w, cropped.h, rotation) }.getOrNull()
                val result = Tasks.await(recognizer.process(input))
                val ms = SystemClock.elapsedRealtime() - t0
                Log.d(TAG, "timing: prep=${tPrep - t0}ms ocr=${SystemClock.elapsedRealtime() - tPrep}ms")
                return@withContext toOutcome(result, frameH, ms).copy(frame = frame)
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
     * 只读亮度平面抽样，做一张 2:1 的小图给界面当预览。
     *
     * 10 fps 也只要几个百分点 CPU——这就是「不绑 Preview 也能有流畅画面」的做法：
     * Preview 那一路出流 + TextureView 每帧合成实测要多花一倍 CPU，而这里复用识别流已有的帧。
     */
    override fun thumbnail(image: ImageProxy, visibleAspect: Float): Bitmap? {
        if (visibleAspect <= 0f) return null
        return runCatching {
            val rect = viewfinderRect(image, visibleAspect)
            val yP = image.planes[0]
            // 按行整块拷出来再抽样：逐字节 get() 每次都要做边界检查，实测那是主要开销。
            val yb = yP.buffer.duplicate()
            val yRow = yP.rowStride
            val yPix = yP.pixelStride
            val step = 4
            val sw = (rect.width() / step).coerceAtLeast(1)
            val sh = ((image.height - rect.top) / step).coerceAtLeast(1)
            val row = ByteArray(rect.width() * yPix)
            val px = IntArray(sw * sh)
            var dst = 0
            for (sy in 0 until sh) {
                yb.position((rect.top + sy * step) * yRow + rect.left * yPix)
                yb.get(row, 0, row.size)
                var si = 0
                repeat(sw) {
                    val g = row[si].toInt() and 0xFF
                    px[dst++] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    si += step * yPix
                }
            }
            val flat = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            flat.setPixels(px, 0, sw, 0, 0, sw, sh)
            val rot = image.imageInfo.rotationDegrees
            if (rot == 0) {
                flat
            } else {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val turned = Bitmap.createBitmap(flat, 0, 0, sw, sh, m, false)
                if (turned !== flat) flat.recycle()
                turned
            }
        }.getOrNull()
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
     * 算出「取景框里那一条」在**未旋转**相机坐标系里的矩形，交给 `ImageProxy.setCropRect`。
     *
     * 取景框是 480×H 的横向框，相机帧是竖构图，`FILL_CENTER` 的缩放系数由宽度定死，
     * 所以屏幕上露出的是**整宽、纵向居中、一段**。这段在原始横构图里是一条竖条，
     * 按 `rotationDegrees` 换算过去就是 RotatedRect 的映射：
     *
     * - `rotation = 90`：`dst(x,y) ← src(y, H-1-x)`
     * - `rotation = 180`：`dst(x,y) ← src(W-1-x, H-1-y)`
     * - `rotation = 270`：`dst(x,y) ← src(W-1-y, x)`
     *
     * 90 与 270 结果相同——居中横条左右对称，只是朝向不同。
     */
    private fun viewfinderRect(image: ImageProxy, boxAspect: Float): Rect {
        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees
        val turned = rotation == 90 || rotation == 270
        val upW = if (turned) h else w
        val upH = if (turned) w else h

        val frameAspect = upH.toFloat() / upW.toFloat().coerceAtLeast(1f)
        val visibleFraction = (1f / boxAspect) / frameAspect
        val cropH = (upH * visibleFraction).toInt().coerceIn(64, upH)
        val top = ((upH - cropH) / 2).coerceAtLeast(0)

        val x0 = 0
        val x1 = upW
        val y0 = top
        val y1 = (top + cropH).coerceAtMost(upH)
        val rect = when (rotation) {
            90 -> Rect(y0, h - x1, y1, h - x0)
            180 -> Rect(w - x1, h - y1, w - x0, h - y0)
            270 -> Rect(w - y1, x0, w - y0, x1)
            else -> Rect(x0, y0, x1, y1)
        }
        // NV21 的色度是 2×2 采样，起止必须落在偶数边界上，否则颜色会串行
        val aligned = Rect(
            (rect.left and 1.inv()).coerceIn(0, w - 2),
            (rect.top and 1.inv()).coerceIn(0, h - 2),
            ((rect.right + 1) and 1.inv()).coerceIn(2, w),
            ((rect.bottom + 1) and 1.inv()).coerceIn(2, h),
        )
        Log.d(
            TAG,
            "crop: frame=${w}x$h rot=$rotation up=${upW}x$upH " +
                "boxAspect=$boxAspect frac=$visibleFraction cropH=$cropH top=$top rect=$aligned",
        )
        return aligned
    }

    /**
     * 把 [rect] 范围内的 YUV_420_888 拷成一块 NV21，直接交给 ML Kit。
     *
     * NV21 排布是「w×h 个 Y，后面跟 (w/2)×(h/2) 个 VU 对」。相机给的三个 plane 各有
     * 自己的 rowStride / pixelStride（UV 常见是 2 像素交错），所以不能整块 memcpy，
     * 得按 stride 逐行取。0.37 MP 在 Kotlin 里约 20~40 ms。
     */
    private fun cropToNv21(image: ImageProxy, rect: Rect): Cropped {
        val w = rect.width()
        val h = rect.height()
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yb = yP.buffer
        val ub = uP.buffer
        val vb = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride

        val arr = ByteArray(w * h * 3 / 2)
        var k = 0
        for (y in 0 until h) {
            var yi = (y + rect.top) * yRow + rect.left * yPix
            repeat(w) {
                arr[k++] = yb.get(yi)
                yi += yPix
            }
        }
        for (y in 0 until h step 2) {
            val row = (y + rect.top) / 2
            var ui = row * uRow + (rect.left / 2) * uPix
            var vi = row * vRow + (rect.left / 2) * vPix
            repeat(w / 2) {
                arr[k++] = vb.get(vi)
                arr[k++] = ub.get(ui)
                ui += uPix
                vi += vPix
            }
        }
        val nv21 = ByteBuffer.allocateDirect(arr.size).apply {
            put(arr)
            rewind()
        }
        // 诊断：确认拷出来的确实是画面内容（全 0 / 全 255 说明 stride 算错了）
        var sum = 0L
        var n = 0
        var mn = 255
        var mx = 0
        var i = 0
        while (i < w * h) {
            val v = arr[i].toInt() and 0xFF
            sum += v
            n++
            if (v < mn) mn = v
            if (v > mx) mx = v
            i += 17
        }
        Log.d(TAG, "nv21: ${w}x$h Y avg=${sum / n.coerceAtLeast(1)} min=$mn max=$mx")
        return Cropped(nv21, arr, w, h)
    }

    /**
     * 把 Y 平面（亮度）转成一张位图，给界面显示「刚才认的是哪一块」。
     *
     * 只用 Y 不看色度：屏幕是单绿，颜色本来就没意义，而灰度图在眼镜上看起来和真实画面一致。
     * 按 2:1 降采样——显示区只有 440×140，1080×344 的原图是纯粹浪费（每像素都要算一次
     * 位运算 + 一次 setPixels）。**只存在内存里，不写文件。**
     */
    private fun yToBitmap(y: ByteArray, w: Int, h: Int, rotation: Int): Bitmap {
        val step = 2
        val sw = (w / step).coerceAtLeast(1)
        val sh = (h / step).coerceAtLeast(1)
        val px = IntArray(sw * sh)
        var dst = 0
        for (sy in 0 until sh) {
            val rowOff = sy * step * w
            for (sx in 0 until sw) {
                val g = y[rowOff + sx * step].toInt() and 0xFF
                px[dst++] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        val flat = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        flat.setPixels(px, 0, sw, 0, 0, sw, sh)
        if (rotation == 0) return flat
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val turned = Bitmap.createBitmap(flat, 0, 0, sw, sh, matrix, false)
        if (turned !== flat) flat.recycle()
        return turned
    }

    /**
     * 裁出来的一帧：给 ML Kit 的 NV21，以及同一块画面的 Y 平面（给界面显示用）。
     */
    private class Cropped(val nv21: ByteBuffer, val y: ByteArray, val w: Int, val h: Int)

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

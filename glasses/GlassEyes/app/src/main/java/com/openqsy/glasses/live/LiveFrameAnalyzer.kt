package com.openqsy.glasses.live

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时取帧分析器。
 *
 * 三件事保证「连续识别」不会把眼镜拖死：
 *
 * 1. **串行**：同一时刻只处理一帧（`AtomicBoolean` CAS），其余帧立刻 `close()` 丢弃。
 *    配合 `STRATEGY_KEEP_ONLY_LATEST`，相机侧也不会积压。
 * 2. **看门狗**：万一 OCR 卡住不返回，超过 [WATCHDOG_MS] 强制放行，
 *    否则 `ImageProxy` 永不释放 → 相机停流（表现为"画面卡死、之后一直不出结果"）。
 * 3. **异步**：`analyze()` 只做 CAS 和 launch，绝不在相机线程上跑 OCR。
 *    任务明确派到 [Dispatchers.Default]：`scope` 传进来时是 `viewModelScope`（Main.immediate），
 *    若不加 dispatcher，`onFrame` 里检索题库那一段（纯 CPU，5 次窗口检索 × 线性选 topK）
 *    会直接压在 UI 线程上——实测一帧渲染 1453 ms、`Skipped 59 frames`。
 *    `onFrame` 内部只改 `StateFlow`，线程安全，不需要主线程。
 *
 * 约定：`onFrame` 的实现方**必须**负责 `image.close()`。
 */
class LiveFrameAnalyzer(
    private val scope: CoroutineScope,
    private val onFrame: suspend (ImageProxy) -> Unit,
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    @Volatile
    private var busySince = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (busy.get() && now - busySince > WATCHDOG_MS) {
            // OCR 疑似挂死：放行，让新帧继续进来
            busy.set(false)
        }
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        busySince = now
        scope.launch(Dispatchers.Default) {
            try {
                onFrame(image)
            } catch (t: Throwable) {
                // onFrame 内部若在 close 前抛异常，这里兜底释放，避免相机停流
                runCatching { image.close() }
            } finally {
                busy.set(false)
            }
        }
    }

    private companion object {
        const val WATCHDOG_MS = 5_000L
    }
}

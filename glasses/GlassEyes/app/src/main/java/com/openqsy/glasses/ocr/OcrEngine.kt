package com.openqsy.glasses.ocr

import androidx.camera.core.ImageProxy

/**
 * 一行识别结果。
 *
 * @param centerY 这一行在**被识别的画面**里的纵向中心，归一化到 0..1（0 = 顶边，1 = 底边）。
 *   有它才能回答「这一屏里哪一行最靠近我框住的中心」，进而把匹配锁定在中间那道题上。
 */
data class OcrLine(val text: String, val centerY: Float)

/** 一次识别的结果（含耗时，便于在眼镜上直接判断性能是否够用）。 */
data class OcrOutcome(
    val text: String,
    val elapsedMs: Long,
    val error: String? = null,
    val lines: List<OcrLine> = emptyList(),
) {
    val ok: Boolean get() = error == null && text.isNotBlank()
}

/**
 * OCR 引擎抽象。
 *
 * 入参是 [ImageProxy] 而不是 Bitmap：整帧识别时可以直接把相机帧交给识别引擎，
 * 省掉一次「解码到 Bitmap → 再转输入张量」的拷贝，这对低功耗眼镜很关键。
 * 需要裁剪时才走 Bitmap 路径（见 [visibleAspect]）。
 *
 * 实现约定：**实现方负责在结束时 `image.close()`**（否则相机会因缓冲区耗尽而卡死）。
 */
interface OcrEngine {
    /** 展示在调试页上的引擎名。 */
    val name: String

    /**
     * 识别一帧。
     *
     * @param visibleAspect 取景框的宽高比（宽/高）。传 0 或负数表示认整帧。
     *   传了值就只认「取景框里能看到的那块」——竖构图下即整宽、纵向居中裁切，
     *   这样「屏幕上看到的」和「实际识别的」是同一块画面，不会把框外的相邻题目也认进来。
     */
    suspend fun recognize(image: ImageProxy, visibleAspect: Float = 0f): OcrOutcome
}

package com.openqsy.glasses.input

import android.view.KeyEvent

/**
 * 单指前滑 / 后滑识别器。
 *
 * 官方文档「按键与佩戴和折叠」：TouchPad 单指滑动**不是广播**，而是
 * **连续两个按下事件（KeyEvent 序列）**，两键间隔 ≤ 500 ms：
 *
 * | 手势 | 键序列 |
 * |---|---|
 * | 单指前滑 | `KEYCODE_DPAD_RIGHT`(22) → `KEYCODE_DPAD_DOWN`(20) |
 * | 单指后滑 | `KEYCODE_DPAD_LEFT`(21) → `KEYCODE_DPAD_UP`(19) |
 *
 * 用法：在 `dispatchKeyEvent` 里对 `ACTION_DOWN && repeatCount == 0` 调用 [onKeyDown]，
 * 返回 `true` 表示已消费（**序列首键也要消费**，否则会漏给焦点导航）。
 */
class SwipeDetector(
    private val onSwipeForward: () -> Unit,
    private val onSwipeBack: () -> Unit,
) {

    private var lastKeyCode = -1
    private var lastEventTime = 0L

    fun onKeyDown(keyCode: Int, eventTime: Long): Boolean {
        if (!isSwipeKey(keyCode)) {
            reset()
            return false
        }
        val prev = if (eventTime - lastEventTime <= MAX_INTERVAL_MS) lastKeyCode else -1
        lastKeyCode = keyCode
        lastEventTime = eventTime

        return when {
            prev == FORWARD_FIRST && keyCode == FORWARD_SECOND -> {
                onSwipeForward()
                reset()
                true
            }

            prev == BACK_FIRST && keyCode == BACK_SECOND -> {
                onSwipeBack()
                reset()
                true
            }

            // 序列首键也消费，避免泄漏给焦点导航
            keyCode == FORWARD_FIRST || keyCode == BACK_FIRST -> true

            // 第二个键但序列不成立（超时/错序）：一并吞掉，避免方向键乱跳焦点
            else -> true
        }
    }

    fun reset() {
        lastKeyCode = -1
        lastEventTime = 0L
    }

    private fun isSwipeKey(keyCode: Int): Boolean = keyCode == FORWARD_FIRST ||
        keyCode == FORWARD_SECOND ||
        keyCode == BACK_FIRST ||
        keyCode == BACK_SECOND

    private companion object {
        const val FORWARD_FIRST = KeyEvent.KEYCODE_DPAD_RIGHT // 22
        const val FORWARD_SECOND = KeyEvent.KEYCODE_DPAD_DOWN // 20
        const val BACK_FIRST = KeyEvent.KEYCODE_DPAD_LEFT     // 21
        const val BACK_SECOND = KeyEvent.KEYCODE_DPAD_UP      // 19
        const val MAX_INTERVAL_MS = 500L
    }
}

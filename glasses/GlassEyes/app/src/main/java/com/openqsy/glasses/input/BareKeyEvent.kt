package com.openqsy.glasses.input

/**
 * 眼镜输入统一语义。
 *
 * 事件定义见开发者文档「按键与佩戴和折叠」专章。
 *
 * - [Click]：`KEYCODE_ENTER`（TouchPad 单指单击）与 `ACTION_SPRITE_BUTTON_CLICK`（镜腿单击）等价
 * - [DoubleClick]：`KEYCODE_BACK`（TouchPad 单指双击）—— **等同系统返回**
 * - [LongPress]：TouchPad 单指长按 · `KEYCODE_PROG_BLUE` 或广播 `ACTION_AI_START`
 * - [SwipeForward]：单指前滑（`DPAD_RIGHT`→`DPAD_DOWN` 键序列）或双指前滑广播
 * - [SwipeBack]：单指后滑（`DPAD_LEFT`→`DPAD_UP` 键序列）或双指后滑广播
 */
enum class BareKeyEvent {
    Click,
    DoubleClick,
    LongPress,
    SwipeForward,
    SwipeBack,
}

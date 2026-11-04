package com.openqsy.glasses.input

/**
 * 系统按键 / TouchPad 有序广播 Action —— 取自 Rokid 官方 GlassesBareDevSample
 * （原位于 `activities/keys/KeyEvents.kt`，本工程删掉示例页后把**核心定义**归位到 input 包）。
 *
 * 事件含义见开发者文档「按键与佩戴和折叠」专章。
 */
object WearFoldActions {
    const val TAKE_STATUS = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
    const val LEG_STATUS = "com.rokid.sprite.ACTION_LEG_STATUS_CHANGED"
    const val EXTRA_TAKE = "glasses_take_state"
    const val EXTRA_LEG = "glasses_leg_state"
}

/** 系统按键 / TouchPad 有序广播 Action（镜腿功能键 + 双指手势）。 */
enum class KeyEventAction(val action: String) {
    /** 镜腿功能键 · 单击 */
    CLICK("com.android.action.ACTION_SPRITE_BUTTON_CLICK"),

    /** 镜腿功能键 · 按下 */
    BUTTON_DOWN("com.android.action.ACTION_SPRITE_BUTTON_DOWN"),

    /** 镜腿功能键 · 抬起（长按已触发时不发送） */
    BUTTON_UP("com.android.action.ACTION_SPRITE_BUTTON_UP"),

    /** 镜腿功能键 · 双击 */
    DOUBLE_CLICK("com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"),

    /** TouchPad 单指长按 · `ACTION_AI_START` → [BareKeyEvent.LongPress]；须 abort 以免启动系统 AI */
    AI_START("com.android.action.ACTION_AI_START"),

    /** 镜腿功能键 · 长按（系统关机流程）；仅 abort */
    LONG_PRESS("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"),

    /** TouchPad 双指 · 单击 */
    TWO_FINGER_SINGLE("com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"),

    /** TouchPad 双指 · 双击 */
    TWO_FINGER_DOUBLE("com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"),

    /** TouchPad 双指 · 前滑 */
    SWIPE_FORWARD("com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"),

    /** TouchPad 双指 · 后滑 */
    SWIPE_BACK("com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"),

    /** TouchPad 双指 · 长按（设置）；仅 abort */
    SETTINGS_KEY("com.android.action.ACTION_SETTINGS_KEY"),
}

/** 日志/调试用的短标签。 */
fun KeyEventAction.logLabel(): String = when (this) {
    KeyEventAction.CLICK -> "广播·镜腿单击"
    KeyEventAction.BUTTON_DOWN -> "广播·镜腿按下"
    KeyEventAction.BUTTON_UP -> "广播·镜腿抬起"
    KeyEventAction.DOUBLE_CLICK -> "广播·镜腿双击"
    KeyEventAction.AI_START -> "广播·TouchPad长按"
    KeyEventAction.LONG_PRESS -> "广播·镜腿长按"
    KeyEventAction.TWO_FINGER_SINGLE -> "广播·双指单击"
    KeyEventAction.TWO_FINGER_DOUBLE -> "广播·双指双击"
    KeyEventAction.SWIPE_FORWARD -> "广播·双指前滑"
    KeyEventAction.SWIPE_BACK -> "广播·双指后滑"
    KeyEventAction.SETTINGS_KEY -> "广播·双指长按"
}

fun keyEventActionLabel(action: String): String =
    KeyEventAction.entries.firstOrNull { it.action == action }?.logLabel()
        ?: action.substringAfterLast('.')

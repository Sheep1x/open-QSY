package com.openqsy.glasses.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat

typealias BareKeyHandler = (BareKeyEvent) -> Boolean

/**
 * 眼镜输入统一分发：TouchPad KeyEvent + 镜腿/TouchPad 有序广播。
 *
 * 事件定义见开发者文档「按键与佩戴和折叠」专章。
 *
 * **Sample UI**（TouchPad 单指 + 镜腿单击）：
 * - 单指单击 → [dispatchEnterKey] · `KEYCODE_ENTER`
 * - 单指双击 → [dispatchBackKey] · `KEYCODE_BACK`
 * - 单指长按 → [dispatchLongKey] · `KEYCODE_PROG_BLUE` 或广播 `ACTION_AI_START`
 * - 镜腿单击 → 广播，映射 [BareKeyEvent.Click]
 *
 * **仅 abort、不分发 UI**：镜腿长按/双击、双指手势等；见 [KeyEventAction] 注册表。
 *
 * 有序广播须在 `onReceive` 内调用 `abortBroadcast()`，避免触发系统 AI / 设置 / 关机等默认行为。
 */
class BareGlassesInputDispatcher(context: Context) {
    private var handler: BareKeyHandler? = null
    private var interceptListener: ((String) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val label = keyEventActionLabel(action)
            val aborted = abortOrderedBroadcast(this)
            notifyIntercept(label, aborted)
            actionToEvent(action)?.let { event -> handler?.invoke(event) }
        }
    }

    init {
        val filter = IntentFilter().apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
            addAction(KeyEventAction.CLICK.action)
            addAction(KeyEventAction.DOUBLE_CLICK.action)
            addAction(KeyEventAction.LONG_PRESS.action)
            addAction(KeyEventAction.TWO_FINGER_SINGLE.action)
            addAction(KeyEventAction.TWO_FINGER_DOUBLE.action)
            addAction(KeyEventAction.SWIPE_FORWARD.action)
            addAction(KeyEventAction.SWIPE_BACK.action)
            addAction(KeyEventAction.AI_START.action)
            addAction(KeyEventAction.SETTINGS_KEY.action)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun setHandler(h: BareKeyHandler?) {
        handler = h
    }

    fun setInterceptListener(listener: ((String) -> Unit)?) {
        interceptListener = listener
    }

    /** 仅当当前 handler 仍为 [expected] 时清空，避免页面切换竞态误清下一页的 handler。 */
    fun clearHandlerIf(expected: BareKeyHandler) {
        if (handler === expected) {
            handler = null
        }
    }

    /** TouchPad 单指单击 · `KEYCODE_ENTER` → [BareKeyEvent.Click] */
    fun dispatchEnterKey() {
        notifyIntercept("Key·ENTER", consumed = true)
        handler?.invoke(BareKeyEvent.Click)
    }

    /** TouchPad 单指双击 · `KEYCODE_BACK` → [BareKeyEvent.DoubleClick] */
    fun dispatchBackKey() {
        notifyIntercept("Key·BACK", consumed = true)
        handler?.invoke(BareKeyEvent.DoubleClick)
    }

    /** TouchPad 单指长按 · `KEYCODE_PROG_BLUE` → [BareKeyEvent.LongPress] */
    fun dispatchLongKey() {
        notifyIntercept("Key·PROG_BLUE", consumed = true)
        handler?.invoke(BareKeyEvent.LongPress)
    }

    /** TouchPad 单指前滑（键序列）/ 双指前滑（广播）→ [BareKeyEvent.SwipeForward] */
    fun dispatchSwipeForward() {
        notifyIntercept("Swipe·前滑", consumed = true)
        handler?.invoke(BareKeyEvent.SwipeForward)
    }

    /** TouchPad 单指后滑（键序列）/ 双指后滑（广播）→ [BareKeyEvent.SwipeBack] */
    fun dispatchSwipeBack() {
        notifyIntercept("Swipe·后滑", consumed = true)
        handler?.invoke(BareKeyEvent.SwipeBack)
    }

    /** 双指长按等仅消费 KeyEvent、不走 UI 的路径。 */
    fun consumeSystemKey(label: String) {
        notifyIntercept(label, consumed = true)
    }

    fun unregister(context: Context) {
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        handler = null
        interceptListener = null
    }

    private fun notifyIntercept(label: String, consumed: Boolean) {
        val suffix = when {
            label.startsWith("Key·") -> " · 已消费"
            consumed -> " · 已拦截"
            else -> " · 未拦截(非有序广播?)"
        }
        interceptListener?.invoke(label + suffix)
    }

    companion object {
        private const val TAG = "BareGlassesInputDispatcher"

        private fun actionToEvent(action: String): BareKeyEvent? = when (action) {
            KeyEventAction.CLICK.action -> BareKeyEvent.Click
            KeyEventAction.AI_START.action -> BareKeyEvent.LongPress
            KeyEventAction.SWIPE_FORWARD.action -> BareKeyEvent.SwipeForward
            KeyEventAction.SWIPE_BACK.action -> BareKeyEvent.SwipeBack
            else -> null
        }

        fun abortOrderedBroadcast(receiver: BroadcastReceiver): Boolean {
            if (!receiver.isOrderedBroadcast) {
                Log.w(TAG, "abort skipped: not ordered broadcast")
                return false
            }
            return try {
                receiver.abortBroadcast()
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "abortBroadcast failed: ${e.message}")
                false
            }
        }
    }
}

typealias BareSpriteKeyDispatcher = BareGlassesInputDispatcher

val LocalBareGlassesInputDispatcher = staticCompositionLocalOf<BareGlassesInputDispatcher?> { null }

val LocalBareSpriteKeyDispatcher = LocalBareGlassesInputDispatcher

@Composable
fun RegisterBareKeyHandler(handler: BareKeyHandler) {
    val dispatcher = LocalBareGlassesInputDispatcher.current ?: return
    val latestHandler by rememberUpdatedState(handler)
    DisposableEffect(dispatcher) {
        val delegate: BareKeyHandler = { event -> latestHandler(event) }
        dispatcher.setHandler(delegate)
        onDispose { dispatcher.clearHandlerIf(delegate) }
    }
}

@Composable
fun rememberBareGlassesInputDispatcher(context: Context): BareGlassesInputDispatcher {
    val appContext = context.applicationContext
    return remember(appContext) { BareGlassesInputDispatcher(appContext) }
}

@Composable
fun rememberBareSpriteKeyDispatcher(context: Context): BareGlassesInputDispatcher =
    rememberBareGlassesInputDispatcher(context)

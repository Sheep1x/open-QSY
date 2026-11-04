package com.openqsy.glasses.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Hub 双击进入子页后，忽略短时间内重复的 [BareKeyEvent.DoubleClick]（同一次双击的尾键）。 */
@Composable
fun rememberSubPageEnterDebounce(windowMs: Long = 400L): () -> Boolean {
    val enteredAt = remember { System.currentTimeMillis() }
    return remember(windowMs) {
        { System.currentTimeMillis() - enteredAt < windowMs }
    }
}

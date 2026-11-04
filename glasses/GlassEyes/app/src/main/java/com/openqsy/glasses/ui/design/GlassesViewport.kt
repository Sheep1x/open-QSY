package com.openqsy.glasses.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.openqsy.glasses.app.CONSTANT
import com.openqsy.glasses.ui.theme.PitchBlack

/**
 * 眼镜物理屏：480×640 px，背景仅 #FF000000（黑=透明不发光）。
 * 内容区固定为该分辨率；更大设备上居中显示，外侧仍为纯黑。
 */
@Composable
fun GlassesDisplayFrame(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density = 1f, fontScale = 1f),
        ) {
            Box(
                modifier = Modifier
                    .glassesScreenPx()
                    .background(PitchBlack)
                    .clipToBounds(),
                content = content,
            )
        }
    }
}

private fun Modifier.glassesScreenPx(): Modifier = layout { measurable, constraints ->
    val width = CONSTANT.SCREEN_WIDTH_PX
    val height = CONSTANT.SCREEN_HEIGHT_PX
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) {
        placeable.place(0, 0)
    }
}

package com.openqsy.glasses.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openqsy.glasses.ui.theme.NeonGreen

data class BareKeyGuide(
    val click: String? = null,
    val doubleClick: String? = null,
    val longPress: String? = null,
    val swipeForward: String? = null,
    val swipeBack: String? = null,
) {
    companion object {
        val Hub = BareKeyGuide(
            click = "下一项",
            doubleClick = "进入",
        )
    }
}

/**
 * 底部按键指引条。
 *
 * 高度是**按行数算出来的**，不是固定的：行高恒为 [BareTokens.LegendRowH]（18dp），
 * 一定大于徽标 16dp，徽标和文字才不会溢出压到下一行。
 * 超出 [BareTokens.LegendH] 的行直接丢掉 —— 宁可少一行提示，也不让字叠起来。
 */
@Composable
fun BareKeyLegendBar(
    guide: BareKeyGuide,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        guide.click?.let { add(KeyRowKind.Click to it) }
        guide.swipeForward?.let { add(KeyRowKind.SwipeForward to it) }
        guide.swipeBack?.let { add(KeyRowKind.SwipeBack to it) }
        guide.doubleClick?.let { add(KeyRowKind.DoubleClick to it) }
        guide.longPress?.let { add(KeyRowKind.LongPress to it) }
    }
    if (rows.isEmpty()) return

    val rowH = BareTokens.LegendRowH
    val dividerH = 1.dp
    val maxRows = ((BareTokens.LegendH - dividerH) / rowH).toInt().coerceAtLeast(1)
    val shown = rows.take(maxRows)
    val totalH = dividerH + rowH * shown.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(totalH),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(dividerH),
        ) {
            drawLine(
                color = NeonGreen,
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = BareTokens.STROKE_THIN,
            )
        }
        shown.forEach { (kind, action) ->
            BareKeyLegendRow(kind = kind, action = action, rowHeight = rowH)
        }
    }
}

private enum class KeyRowKind(val badge: String) {
    Click("单击"),
    SwipeForward("前滑"),
    SwipeBack("后滑"),
    DoubleClick("双击"),
    LongPress("长按"),
}

@Composable
private fun BareKeyLegendRow(kind: KeyRowKind, action: String, rowHeight: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BareKeyBadge(label = kind.badge)
        Text(
            text = action,
            color = NeonGreen,
            fontSize = BareTokens.LegendSp,
            lineHeight = BareTokens.LegendSp * 1.3f,
            // 必须锁一行：文案一换行就会撑高行高，把下一行顶出去，看起来就是重叠
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 6.dp)
                .weight(1f),
        )
    }
}

@Composable
private fun BareKeyBadge(label: String) {
    val badgeW = if (label.length <= 2) 36.dp else 40.dp
    Box(
        modifier = Modifier
            .width(badgeW)
            .height(BareTokens.LegendBadgeH),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                color = NeonGreen,
                style = Stroke(width = BareTokens.STROKE_THIN),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        Text(
            text = label,
            color = NeonGreen,
            fontSize = BareTokens.CaptionSp,
            lineHeight = BareTokens.CaptionSp * 1.2f,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

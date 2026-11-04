package com.openqsy.glasses.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openqsy.glasses.app.CONSTANT
import com.openqsy.glasses.ui.theme.NeonGreen
import com.openqsy.glasses.ui.theme.PitchBlack

/**
 * 全屏 480×640，主内容与线框落在安全区 y=80～560；底部为结构化按键指引。
 */
@Composable
fun BareScreenLayout(
    title: String,
    keyGuide: BareKeyGuide,
    pageIndex: Int? = null,
    pageCount: Int? = null,
    subtitle: String? = null,
    drawSafeAreaFrame: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    val topPad = CONSTANT.SAFE_AREA_TOP_PX.dp
    val bottomPad = (CONSTANT.SCREEN_HEIGHT_PX - CONSTANT.SAFE_AREA_BOTTOM_PX).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PitchBlack),
    ) {
        if (drawSafeAreaFrame) {
            SafeAreaFrame(modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = BareTokens.ScreenPadH,
                    end = BareTokens.ScreenPadH,
                    top = topPad + 4.dp,
                    bottom = bottomPad + 4.dp,
                ),
        ) {
            BareScreenHeader(
                title = title,
                subtitle = subtitle,
                pageIndex = pageIndex,
                pageCount = pageCount,
            )
            BareContentPanel(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                content = body,
            )
            BareKeyLegendBar(guide = keyGuide)
        }
    }
}

@Composable
fun BareDevScaffold(
    title: String,
    keyHint: String,
    subtitle: String? = null,
    drawSafeArea: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BareScreenLayout(
        title = title,
        subtitle = subtitle,
        keyGuide = parseLegacyKeyHint(keyHint),
        drawSafeAreaFrame = drawSafeArea,
        body = content,
    )
}

private fun parseLegacyKeyHint(hint: String): BareKeyGuide {
    var click: String? = null
    var doubleClick: String? = null
    var longPress: String? = null
    hint.split("·").map { it.trim() }.forEach { part ->
        when {
            part.startsWith("单击") ->
                click = part.removePrefix("单击").removePrefix("：").trim().ifEmpty { "确认" }
            part.startsWith("双击") ->
                doubleClick = part.removePrefix("双击").removePrefix("：").trim().ifEmpty { "返回" }
            part.startsWith("长按") ->
                longPress = part.removePrefix("长按").removePrefix("：").trim()
        }
    }
    if (click == null && doubleClick == null && longPress == null && hint.isNotBlank()) {
        click = hint
    }
    return BareKeyGuide(click = click, doubleClick = doubleClick, longPress = longPress)
}

@Composable
private fun BareScreenHeader(
    title: String,
    subtitle: String?,
    pageIndex: Int?,
    pageCount: Int?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BareTokens.HeaderH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // 标题 + 副标题两行合计可能超过 HeaderH，裁掉而不是压到下面的内容面板上
                .clipToBounds(),
        ) {
            Text(
                text = title,
                color = NeonGreen,
                fontSize = BareTokens.TitleSp,
                lineHeight = BareTokens.TitleSp * 1.3f,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = NeonGreen.copy(alpha = 0.75f),
                    fontSize = BareTokens.SubtitleSp,
                    lineHeight = BareTokens.SubtitleSp * 1.3f,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (pageIndex != null && pageCount != null && pageCount > 1) {
            BarePageDots(current = pageIndex, total = pageCount)
        }
    }
}

@Composable
fun BarePageDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total.coerceIn(1, 8)) { i ->
            Canvas(modifier = Modifier.size(8.dp)) {
                val r = 3.5f
                val cx = size.width / 2f
                val cy = size.height / 2f
                if (i == current) {
                    drawCircle(NeonGreen, radius = r, center = Offset(cx, cy))
                } else {
                    drawCircle(NeonGreen, radius = r, center = Offset(cx, cy), style = Stroke(1.5f))
                }
            }
        }
    }
}

@Composable
fun BareContentPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.padding(vertical = 4.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = NeonGreen,
                style = Stroke(width = BareTokens.STROKE_NORMAL),
                topLeft = Offset(1f, 1f),
                size = Size(size.width - 2f, size.height - 2f),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(BareTokens.PanelPad)
                // 关键：内容超出面板时裁掉，否则会画到下面的按键提示条上（看起来像"文字重叠"）
                .clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(BareTokens.LineGap),
            content = content,
        )
    }
}

@Composable
fun SafeAreaFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val top = CONSTANT.SAFE_AREA_TOP_PX.toFloat()
        val bottom = CONSTANT.SAFE_AREA_BOTTOM_PX.toFloat()
        drawRect(
            color = NeonGreen.copy(alpha = 0.45f),
            style = Stroke(width = BareTokens.STROKE_THIN),
            topLeft = Offset(0.5f, top),
            size = Size(size.width - 1f, bottom - top),
        )
    }
}

@Composable
fun BareHeroText(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            color = NeonGreen,
            fontSize = BareTokens.HeroSp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        hint?.let {
            Text(
                text = it,
                color = NeonGreen.copy(alpha = 0.7f),
                fontSize = BareTokens.BodySp,
                lineHeight = BareTokens.BodyLineH,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun BareInfoBlock(
    label: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = NeonGreen,
            fontSize = BareTokens.BodySp,
            fontWeight = FontWeight.Medium,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(1.dp),
        ) {
            drawLine(
                NeonGreen.copy(alpha = 0.5f),
                Offset(0f, 0f),
                Offset(size.width, 0f),
                strokeWidth = BareTokens.STROKE_THIN,
            )
        }
        lines.filter { it.isNotBlank() }.forEach { line ->
            Text(
                text = line,
                color = NeonGreen,
                fontSize = BareTokens.BodySp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                lineHeight = BareTokens.BodyLineH,
            )
        }
    }
}

@Composable
fun BarePagedViewport(
    pageIndex: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (pageIndex: Int) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        content(pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }
}

/** @deprecated 使用 [BareHeroText] / [BareInfoBlock] */
@Composable
fun BareActionHint(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    BareHeroText(text = label, modifier = modifier, hint = if (emphasized) null else null)
}

/** @deprecated 使用 [BareInfoBlock] */
@Composable
fun BareStatusCard(title: String, lines: List<String>, modifier: Modifier = Modifier) {
    BareInfoBlock(label = title, lines = lines, modifier = modifier)
}

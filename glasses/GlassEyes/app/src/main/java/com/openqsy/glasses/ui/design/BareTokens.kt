package com.openqsy.glasses.ui.design

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 480×640 @ density=1：字号与间距按像素对齐规范。
 *
 * 高度预算（必须恒等，改任何一项都要重算）：
 * ```
 * 640  屏幕
 * -84  top 安全区 80 + 4
 * -84  bottom 安全区 80 + 4
 * =472 净可用
 * -42  HeaderH
 * -58  Legend（3 行：4 + 3×18）
 * =372 内容面板外框（含上下各 4 的间距）
 * -20  PanelPad ×2
 * =344 正文可用高度
 * ```
 * 任何一行的 [lineHeight] 都必须 ≥ 字号的 1.3 倍，否则中文字形上下相碰，
 * 在单色绿屏上看起来就是"两行字叠在一起"。
 */
object BareTokens {
    const val STROKE_THIN = 1f
    const val STROKE_NORMAL = 2f
    const val STROKE_EMPHASIS = 2.5f

    val ScreenPadH = 10.dp

    /** 标题 15sp(≈20px) + 副标题 11sp(≈15px) = 35px，必须留出余量。 */
    val HeaderH = 42.dp

    /** 图例条上限：4 行 × 18 + 4 = 76，留 8 余量。 */
    val LegendH = 84.dp

    /** 图例单行高度：必须 > 徽标 16dp，否则徽标溢出压到相邻行。 */
    val LegendRowH = 18.dp

    /** 徽标外框高度。 */
    val LegendBadgeH = 16.dp

    val PanelPad = 10.dp
    val LineGap = 6.dp

    /**
     * 主界面取景框高度（只在「还没认出题」时显示）。
     *
     * 相机输出是竖构图（约 3:4），在 480 宽的横向框里只有 FILL 才用得上宽度：
     * scale 由宽度定死（480/768≈0.625），框多高就露出源画面中间多高——
     * 120px 只露 19%，220px 露 34%，肉眼才能判断「对着的是不是这一块题目」。
     * 用 FIT 反而只剩中间一条 150px 宽的窄画，左右全黑，更看不清。
     *
     * 220 是上限：面板正文 329px，取景框 + 间隙吃掉 226px，剩 103px，
     * 刚好放「对准题目 + 一行提示 + 识别到 2 行 OCR 原文」。
     * 再高就得把 OCR 原文整段砍掉，而那正是判断「没认出来」的依据。
     */
    val ViewfinderH = 140.dp

    val TitleSp = 15.sp
    val SubtitleSp = 11.sp
    val BodySp = 13.sp
    val BodyLargeSp = 14.sp
    val CaptionSp = 10.sp
    val LegendSp = 11.sp
    val HeroSp = 17.sp
    val AnswerSp = 22.sp

    /** 长答案（选项原文、简答与计算题的解答）降一档，换取更多行数预算。 */
    val AnswerSmallSp = 16.sp

    // 行高：一律 ≥ 字号 × 1.3，中文字形才不会被压到互相重叠。
    val AnswerLineH = 28.sp
    val AnswerSmallLineH = 21.sp
    val BodyLineH = 18.sp
    val CaptionLineH = 14.sp
}

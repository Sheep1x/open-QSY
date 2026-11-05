#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
480x640 单绿屏布局模拟器。

用途：眼镜的 screencap / uiautomator 都抓不到应用层（系统显示由 com.rokid.sysconfig
接管），所以用这个脚本按 Compose 的排布规则在 PC 上精确重算一遍，做三件事：

1. 输出 480x640 的预期渲染图，肉眼比对
2. 逐块记录 bbox，做**矩形相交检测**，证明没有重叠
3. 校验所有块都落在安全区 y=80~560 内

参数必须与 BareTokens.kt / BareDesign.kt / BareKeyLegend.kt / QuizScreen.kt 保持一致。
改了 Kotlin 那边就同步改这里，否则验证失去意义。
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- 设计常量
W, H = 480, 640
SAFE_TOP, SAFE_BOTTOM = 80, 560

SCREEN_PAD_H = 10
HEADER_H = 42
LEGEND_H = 84
LEGEND_ROW_H = 18
LEGEND_BADGE_H = 16
PANEL_PAD = 10
LINE_GAP = 6
VIEWFINDER_H = 140      # 相机取景框（还没出答案），对应 BareTokens.ViewfinderH
VIEWFINDER_COMPACT_H = 84  # 出了答案后的取景框（缩一档但仍可见），对应 BareTokens.ViewfinderCompactH
PANEL_VPAD = 4          # BareContentPanel 的 padding(vertical = 4.dp)
DIVIDER_VPAD = 4        # Divider 的 padding(vertical = 4.dp)

TITLE_SP, SUBTITLE_SP = 15, 11
BODY_SP, BODY_LARGE_SP, CAPTION_SP = 13, 14, 10
LEGEND_SP, HERO_SP, ANSWER_SP = 11, 17, 22
ANSWER_LH, BODY_LH, CAPTION_LH = 28, 18, 14

GREEN = (0, 255, 0)
BLACK = (0, 0, 0)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhl.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for p in FONT_CANDIDATES:
        if Path(p).exists():
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    raise SystemExit("找不到中文字体，无法渲染")


@dataclass
class Block:
    """一个已排布好的文本/图形块。"""
    name: str
    x: int
    y: int
    w: int
    h: int
    lines: list[str] = field(default_factory=list)
    size: int = 0
    line_h: int = 0
    color: tuple = GREEN
    stroke_only: bool = False

    @property
    def box(self) -> tuple[int, int, int, int]:
        return self.x, self.y, self.x + self.w, self.y + self.h

    def intersects(self, o: "Block") -> bool:
        ax0, ay0, ax1, ay1 = self.box
        bx0, by0, bx1, by1 = o.box
        # 边界相接（共享一条线）不算重叠
        return ax0 < bx1 and bx0 < ax1 and ay0 < by1 and by0 < ay1


def wrap(text: str, font: ImageFont.FreeTypeFont, max_w: int) -> list[str]:
    """按像素宽度折行，规则接近 Compose 的 soft wrap（中文逐字、英文按词）。"""
    if not text:
        return [""]
    lines: list[str] = []
    cur = ""
    for ch in text:
        trial = cur + ch
        if font.getlength(trial) <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def layout_text(
    blocks: list[Block],
    name: str,
    text: str,
    x: int,
    y: int,
    max_w: int,
    size: int,
    line_h: int,
    max_lines: int,
) -> int:
    """排一个 Text，返回占用高度。"""
    font = load_font(size)
    lines = wrap(text, font, max_w)[:max_lines]
    h = line_h * max(1, len(lines))
    blocks.append(
        Block(
            name=name,
            x=x,
            y=y,
            w=max_w,
            h=h,
            lines=lines,
            size=size,
            line_h=line_h,
        )
    )
    return h


def layout_divider(blocks: list[Block], x: int, y: int, w: int) -> int:
    h = DIVIDER_VPAD * 2 + 1
    blocks.append(Block(name="divider", x=x, y=y, w=w, h=h, stroke_only=True))
    return h


# ---------------------------------------------------------------- 页面构建
def build_main_page(
    has_hit: bool,
    ocr_preview: str,
    paused: bool,
    answer_text: str = "B。因为加速度方向与速度方向相反",
    stem_text: str = "一小球沿斜面下滑，下列说法正确的是（　）",
    answer_sp: int = ANSWER_SP,
    answer_lh: int = ANSWER_LH,
    answer_max: int = 3,
    stem_max: int = 4,
    viewfinder_h: int = 0,   # 取景框只在「还没认出题」时显示
) -> list[Block]:
    blocks: list[Block] = []

    left = SCREEN_PAD_H
    content_w = W - SCREEN_PAD_H * 2

    # ---- Header（固定 42，内部裁切）
    y = SAFE_TOP + 4
    layout_text(blocks, "title", "题眼 · 拍照", left, y, content_w,
                TITLE_SP, round(TITLE_SP * 1.3), 1)
    sub = "拍照中 · 已拍 3 张"
    layout_text(blocks, "subtitle", sub, left, y + round(TITLE_SP * 1.3), content_w,
                SUBTITLE_SP, round(SUBTITLE_SP * 1.3), 1)
    y += HEADER_H

    # ---- Legend（自底向上确定，4 行）
    # 对齐 BareScreenLayout：Column 的 bottom padding = (640-560) + 4 = 84，
    # 所以 Column 内容区底边是 640-84 = 556，不是 SAFE_BOTTOM+4。
    column_bottom = H - (H - SAFE_BOTTOM) - 4
    rows = [("单击", "暂停"), ("前滑", "换候选（前/后滑）"), ("双击", "返回"), ("长按", "调试")]
    rows = rows[: max(1, (LEGEND_H - 1) // LEGEND_ROW_H)]
    legend_h = 1 + LEGEND_ROW_H * len(rows)
    legend_y = column_bottom - legend_h

    # ---- ContentPanel
    panel_y = y
    panel_h = legend_y - y
    blocks.append(
        Block(name="panel.frame", x=left, y=panel_y + PANEL_VPAD,
              w=content_w, h=panel_h - PANEL_VPAD * 2, stroke_only=True)
    )

    ix = left + PANEL_PAD
    iw = content_w - PANEL_PAD * 2
    cy = panel_y + PANEL_VPAD + PANEL_PAD

    # ---- 取景框：相机实时预览条，紧贴面板顶部（对应 BareTokens.ViewfinderH）
    if viewfinder_h:
        blocks.append(
            Block(name="viewfinder", x=ix, y=cy, w=iw, h=viewfinder_h, stroke_only=True)
        )
        cy += viewfinder_h + LINE_GAP

    def gap() -> None:
        nonlocal cy
        cy += LINE_GAP

    if has_hit:
        h = layout_text(blocks, "answer.caption", "答案 ✓", ix, cy, iw,
                        CAPTION_SP, CAPTION_LH, 1)
        cy += h; gap()
        h = layout_text(blocks, "answer", answer_text, ix, cy, iw,
                        answer_sp, answer_lh, answer_max)
        cy += h; gap()
        h = layout_divider(blocks, ix, cy, iw)
        cy += h; gap()
        h = layout_text(blocks, "stem", stem_text, ix, cy, iw,
                        BODY_SP, BODY_LH, stem_max)
        cy += h; gap()
        h = layout_divider(blocks, ix, cy, iw)
        cy += h; gap()
        layout_text(blocks, "meta", "匹配 0.87 · 物理 · 候选 1/3 · 420/6ms", ix, cy, iw,
                    CAPTION_SP, CAPTION_LH, 1)
    else:
        h = layout_text(blocks, "hint.title", "已暂停" if paused else "对准题目", ix, cy, iw,
                        18, 24, 1)
        cy += h; gap()
        h = layout_text(blocks, "hint.sub", "单击继续扫描" if paused else "把题干放进画面中央", ix, cy, iw,
                        BODY_SP, BODY_LH, 1 if viewfinder_h else 2)
        cy += h; gap()
        h = layout_divider(blocks, ix, cy, iw)
        cy += h; gap()
        h = layout_text(blocks, "ocr.label", "识别到：", ix, cy, iw, CAPTION_SP, CAPTION_LH, 1)
        cy += h; gap()
        layout_text(blocks, "ocr.text", ocr_preview, ix, cy, iw, CAPTION_SP, CAPTION_LH, 5)

    # ---- Legend 内容
    ly = legend_y
    blocks.append(Block(name="legend.rule", x=left, y=ly, w=content_w, h=1, stroke_only=True))
    ly += 1
    for badge, action in rows:
        bw = 36 if len(badge) <= 2 else 40
        blocks.append(Block(name=f"legend.badge.{badge}", x=left + 2, y=ly + (LEGEND_ROW_H - LEGEND_BADGE_H) // 2,
                            w=bw, h=LEGEND_BADGE_H, lines=[badge], size=CAPTION_SP,
                            line_h=round(CAPTION_SP * 1.2), stroke_only=True))
        layout_text(blocks, f"legend.action.{badge}", action, left + 2 + bw + 6, ly + (LEGEND_ROW_H - round(LEGEND_SP * 1.3)) // 2,
                    content_w - 2 - bw - 6, LEGEND_SP, round(LEGEND_SP * 1.3), 1)
        ly += LEGEND_ROW_H

    return blocks


# ---------------------------------------------------------------- 渲染 + 校验
def render(blocks: list[Block], out: Path) -> None:
    img = Image.new("RGB", (W, H), BLACK)
    d = ImageDraw.Draw(img)
    for b in blocks:
        if b.stroke_only:
            if b.h <= 2:  # 分割线
                d.line([(b.x, b.y), (b.x + b.w, b.y)], fill=b.color, width=1)
            elif b.name.startswith("legend.badge"):
                d.rounded_rectangle([b.x, b.y, b.x + b.w - 1, b.y + b.h - 1],
                                    radius=2, outline=b.color, width=1)
                f = load_font(b.size)
                tw = f.getlength(b.lines[0])
                d.text((b.x + (b.w - tw) / 2, b.y + (b.h - b.line_h) / 2),
                       b.lines[0], font=f, fill=b.color)
            else:
                d.rectangle([b.x, b.y, b.x + b.w - 1, b.y + b.h - 1],
                            outline=b.color, width=2)
        else:
            f = load_font(b.size)
            for i, line in enumerate(b.lines):
                d.text((b.x, b.y + i * b.line_h), line, font=f, fill=b.color)
    img.save(out)


def verify(blocks: list[Block]) -> list[str]:
    problems: list[str] = []

    # 1. 两两相交
    for i in range(len(blocks)):
        for j in range(i + 1, len(blocks)):
            a, b = blocks[i], blocks[j]
            # 边框与内部内容是包含关系，不算重叠
            if a.name == "panel.frame" or b.name == "panel.frame":
                continue
            if a.intersects(b):
                problems.append(f"重叠: {a.name} {a.box} × {b.name} {b.box}")

    # 2. 安全区
    for b in blocks:
        if b.y < SAFE_TOP or b.y + b.h > SAFE_BOTTOM:
            problems.append(f"越界安全区: {b.name} y={b.y}..{b.y + b.h}")

    # 3. 画布内
    for b in blocks:
        if b.x < 0 or b.x + b.w > W or b.y + b.h > H:
            problems.append(f"出画布: {b.name} {b.box}")

    return problems


def report(blocks: list[Block]) -> None:
    print(f"{'块':<24}{'x':>5}{'y':>6}{'w':>6}{'h':>6}  内容")
    print("-" * 78)
    for b in blocks:
        txt = b.lines[0] if b.lines else ""
        if len(txt) > 22:
            txt = txt[:22] + "…"
        print(f"{b.name:<24}{b.x:>5}{b.y:>6}{b.w:>6}{b.h:>6}  {txt}")


def main() -> int:
    out_dir = Path(__file__).resolve().parent.parent / "shots"
    out_dir.mkdir(parents=True, exist_ok=True)

    cases = {
        "hit": build_main_page(True, "", False, viewfinder_h=VIEWFINDER_COMPACT_H),
        # 最坏情况：答案 3 行 + 题干 4 行 + OCR 5 行全部占满
        "worst": build_main_page(
            True,
            "",
            False,
            answer_text="B。因为加速度方向与速度方向相反，速度不断减小但加速度保持不变，"
                        "所以小球做匀减速直线运动直到速度为零，之后保持静止状态不再运动。",
            viewfinder_h=VIEWFINDER_COMPACT_H,
            stem_text="一小球沿倾角为三十度的光滑斜面由静止开始下滑，下列说法中正确的是（　）"
                      "A 小球的加速度大小为 g　B 小球的加速度大小为 g·sin30°　"
                      "C 小球的机械能守恒　D 小球的动量守恒",
        ),
        "scan": build_main_page(False, "一小球沿斜面下滑 下列说法正确的是 A 加速度 B 速度 C 位移 D 时间", False,
                                viewfinder_h=VIEWFINDER_H),
        "paused": build_main_page(False, "", True, viewfinder_h=VIEWFINDER_H),
        # 答案带选项原文的多选题 + 长答案降档规则（16sp / 21 行高 / 10 行上限，题干压到 2 行）
        "long": build_main_page(
            True,
            "",
            False,
            answer_text="A. 土地使用权出让合同约定的使用年限届满，土地使用者需要继续使用土地的，"
                        "应当最迟于届满前一年申请续期\n"
                        "B. 除根据社会公共利益需要收回该幅土地的，出让方应当予以批准",
            stem_text="关于土地使用权出让年限届满后的处理，下列说法正确的是（　）",
            answer_sp=16,
            answer_lh=21,
            answer_max=7,
            stem_max=1,
            viewfinder_h=VIEWFINDER_COMPACT_H,
        ),
        # 压力测试：答案远超 10 行上限，必须被截断而不是把下面的内容顶出面板
        "stress": build_main_page(
            True,
            "",
            False,
            answer_text="【第一小题解答】1.出售部分 1）销售收入：13000×6500＝8450（万元）"
                        "2）销售税费：8450×5.5%＝464.75（万元）2.出租部分 1）潜在毛租金收入："
                        "100×330×365＝1204.5（万元）2）有效毛租金收入：经营期第1～3年："
                        "1204.5×70%＝843.15（万元）经营期第4～20年：1204.5×85%＝1023.83（万元）"
                        "3）运营费用：经营期第1～3年：843.15×30%＝252.95（万元）"
                        "经营期第4～20年：1023.83×35%＝358.34（万元）4）净收益："
                        "843.15－252.95＝590.20（万元）1023.83－358.34＝665.49（万元）",
            stem_text="某房地产开发项目，请计算其净收益与开发价值。",
            answer_sp=16,
            answer_lh=21,
            answer_max=7,
            stem_max=1,
            viewfinder_h=VIEWFINDER_COMPACT_H,
        ),
    }

    failed = False
    for name, blocks in cases.items():
        png = out_dir / f"layout_{name}.png"
        render(blocks, png)
        problems = verify(blocks)
        print(f"\n=== {name} ===  块数 {len(blocks)}  -> {png.name}")
        report(blocks)
        if problems:
            failed = True
            print("\n!! 发现问题:")
            for p in problems:
                print("  -", p)
        else:
            print("\nOK: 无重叠、无越界")
            tail = max(b.y + b.h for b in blocks)
            print(f"最低点 y={tail}（安全区底 {SAFE_BOTTOM}，余量 {SAFE_BOTTOM - tail}px）")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

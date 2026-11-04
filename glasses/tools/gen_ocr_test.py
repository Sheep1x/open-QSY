"""生成「一屏三道题」的测试图，用来验证中心聚焦匹配。

真机上没法远程让相机对准某张纸，所以用固定图片跑 `--es ocrimg`。
排版刻意让第 2 题落在画面正中间：裁剪后（纵向居中约 35%）应当只命中第 2 题。
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

BANK = Path(r"D:\openwork\abcdefg\题库导出\gugu.json")
OUT = Path(__file__).resolve().parent.parent / "shots" / "ocr_3q.png"

W, H = 768, 1024
FONT = r"C:\Windows\Fonts\msyh.ttc"
FS = 26
LH = 38


def wrap(draw, text, font, max_w):
    lines, cur = [], ""
    for ch in text:
        if draw.textlength(cur + ch, font=font) > max_w:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines


def main():
    data = json.loads(BANK.read_text(encoding="utf-8"))
    qs = data["questions"]

    # 三题按固定 y 摆放，每题只占 2 行（题干 + 答案）：
    # 裁剪窗口是 336~687，正好同时框住第 2 题（430~506）和第 3 题（610~686）的开头，
    # 而第 2 题更靠近画面中心——用来验证「多题同屏时命中中间那道」。
    img = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, FS)

    ys = [250, 430, 610]
    blocks = []
    for i in range(3):
        q = qs[i]
        stem = q.get("stem", "").strip()
        if len(stem) > 22:
            stem = stem[:22] + "……"
        ans = q.get("answer", "").strip().split("\n")[0]
        rows = wrap(d, f"{i + 1}. {stem}", f, W - 60)
        rows.append("答：" + ans)
        blocks.append((ys[i], rows))

    for y0, rows in blocks:
        y = y0
        for r in rows:
            d.text((30, y), r, fill="black", font=f)
            y += LH

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"saved {OUT} {img.size} {OUT.stat().st_size} bytes")
    for i, (y0, rows) in enumerate(blocks):
        print(f"Q{i + 1} @y={y0}: {rows[0][:50]}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()

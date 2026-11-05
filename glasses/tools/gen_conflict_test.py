"""生成三张「条件校验」测试图：

1. 原题（与题库一模一样）      → 应该给出答案
2. 原题但改了两个数字          → 应该提示「题干与题库不符（数字 …）」
3. 原题但「多层建筑指」改成「不包括」 → 应该提示「题干与题库不符（多了「不包括」）」

用法：python tools/gen_conflict_test.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

FONT = r"C:\Windows\Fonts\msyh.ttc"
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "shots")

T1 = [
    "某建筑物的±0.000=26.800m，地下室底板标高-6.400m，",
    "该建筑物地下室底板的绝对标高为（　）。",
    "A. 19.400m",
    "B. 20.400m",
    "C. 32.600m",
    "D. 33.200m",
]

T2 = [
    "某建筑物的±0.000=36.800m，地下室底板标高-7.400m，",
    "该建筑物地下室底板的绝对标高为（　）。",
    "A. 19.400m",
    "B. 20.400m",
    "C. 32.600m",
    "D. 33.200m",
]

T3 = [
    "根据《城市居住区规划设计标准》GB 50180—2018，",
    "按建筑物的层数分类，多层建筑不包括（　）层的建筑。",
    "A. 1～3",
    "B. 4～6",
    "C. 7～9",
]


def render(lines, path):
    img = Image.new("RGB", (768, 1024), "white")
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 34)
    y = 260
    for line in lines:
        d.text((44, y), line, font=font, fill=(0, 0, 0))
        y += 52
    img.save(path)
    print(os.path.basename(path), img.size, os.path.getsize(path), "B")


for name, lines in (("conflict_1_raw.png", T1), ("conflict_2_num.png", T2), ("conflict_3_neg.png", T3)):
    render(lines, os.path.join(OUT, name))

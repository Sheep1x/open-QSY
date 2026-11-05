# -*- coding: utf-8 -*-
"""把 PDF 文本导成干净的 .txt，并打印分区结构。"""
import re

from pypdf import PdfReader

PDF = r"C:\Users\13334\Desktop\06-第六部分　习题模拟_答案分离.pdf"
OUT = r"D:\openwork\open-qsy\glasses\shots\pdf06.txt"

r = PdfReader(PDF)
pages = [pg.extract_text() or "" for pg in r.pages]

DROP = [
    re.compile(r"^#DownID#.*$", re.M),
    re.compile(r"^房地产估价师《房估实务》.*$", re.M),
    re.compile(r"^\s*第\d+页\s*$", re.M),
]

full = "\n".join(pages)
for pat in DROP:
    full = pat.sub("", full)

# 中文断行：页宽造成的折行（上一行不以标点结尾、下一行不是题号/选项时合并）
lines = [l.rstrip() for l in full.splitlines()]
out = []
for l in lines:
    s = l.strip()
    if not s:
        continue
    out.append(s)
text = "\n".join(out)

with open(OUT, "w", encoding="utf-8") as f:
    f.write(text)
print("written:", OUT, len(text), "chars")

# 分区定位
for kw in ("一、不定项选择题", "二、综合分析题", "三、问答题", "习题参考答案"):
    idx = text.find(kw)
    print(f"{kw!r} @ {idx}")

# 答案区题号分布
ans_start = text.find("习题参考答案")
ans = text[ans_start:]
nums = [int(m.group(1)) for m in re.finditer(r"^(\d+)、「正确答案」", ans, re.M)]
print("答案题号:", nums)
print("答案题数:", len(nums))

# 题目区题号（"N、" 开头，后接 "N." 或空格）
body = text[:ans_start]
qn = [int(m.group(1)) for m in re.finditer(r"^(\d+)、", body, re.M)]
print("题目区题号:", qn)

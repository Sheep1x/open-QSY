# -*- coding: utf-8 -*-
"""探查 PDF 结构：每页长度、答案分布。"""
import re
import sys

from pypdf import PdfReader

PDF = r"C:\Users\13334\Desktop\06-第六部分　习题模拟_答案分离.pdf"

r = PdfReader(PDF)
pages = [pg.extract_text() or "" for pg in r.pages]
print("pages:", len(pages), "chars:", sum(len(t) for t in pages))

for i, t in enumerate(pages):
    lines = t.splitlines()
    third = lines[3][:40] if len(lines) > 3 else ""
    has_ans = any(("答案" in l or "解析" in l) for l in lines)
    print(f"p{i+1:02d} {len(t):5d}ch ans={int(has_ans)} | {third}")

full = "\n".join(pages)
print("\n#### 含「答案」的片段 ####")
for m in list(re.finditer(r".{0,25}答案.{0,60}", full))[:15]:
    print(repr(m.group()))

print("\n#### 最后 900 字 ####")
print(full[-900:])

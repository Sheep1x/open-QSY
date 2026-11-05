# -*- coding: utf-8 -*-
"""把 pdf06_bank.json 合并进 gugu.json，产出最终题库。"""
import io
import json
import re
import unicodedata

GUGU = r"D:\openwork\abcdefg\题库导出\gugu.json"
PDF = r"D:\openwork\open-qsy\glasses\shots\pdf06_bank.json"
OUT = r"D:\openwork\abcdefg\题库导出\gugu-merged.json"


def norm(s):
    """与端上 TextNormalizer 同规则：NFKC + 小写 + 只留字母和 ASCII 数字。"""
    s = unicodedata.normalize("NFKC", s or "").lower()
    return "".join(ch for ch in s if ch.isalpha() or (ch.isascii() and ch.isdigit()))


g = json.load(io.open(GUGU, encoding="utf-8"))
p = json.load(io.open(PDF, encoding="utf-8"))

base = g["questions"]
added = p["questions"]

seen = {}
for q in base:
    seen.setdefault(norm(q["stem"]), q)

dups, fresh = [], []
for q in added:
    k = norm(q["stem"])
    if k in seen:
        dups.append(q)
    else:
        seen[k] = q
        fresh.append(q)

merged = base + fresh
meta = dict(g["meta"])
meta["title"] = f"房地产估价师题库（{len(merged)}题）"
meta["source"] = g["meta"]["source"] + " + 《房地产估价师《房估实务》第六部分 习题模拟》PDF"
meta["count"] = len(merged)
meta["fields"] = "id,stem,answer,subject,type,chapter,analysis"

json.dump({"meta": meta, "questions": merged},
          io.open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

print("原题库:", len(base))
print("PDF 题:", len(added), " 去重后新增:", len(fresh), " 与原有重复:", len(dups))
print("合并后:", len(merged), "->", OUT)
for q in dups:
    print("  重复:", q["stem"][:40])

import os
print("体积:", os.path.getsize(OUT), "B")
import collections
c = collections.Counter(q["subject"] for q in merged)
for k, v in c.most_common():
    print(f"  {k}: {v}")
c2 = collections.Counter(q["type"] for q in fresh)
print(" 新增题型:", dict(c2))

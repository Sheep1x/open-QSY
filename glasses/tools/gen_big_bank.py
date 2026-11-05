"""造一份「大题量」题库，用来量设备端编译与检索的规模上限。

做法：把现有题库复制 N 份，每份的题干后缀加「（变体k）」保证归一化后唯一，
答案与科目原样保留。这样题量涨 N 倍、文本池也涨 N 倍，最接近「题库变大」的真实情形。

用法：
    python gen_big_bank.py <源题库.json> <输出.json> [份数]
"""
import json
import sys


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, dst = sys.argv[1], sys.argv[2]
    copies = int(sys.argv[3]) if len(sys.argv) > 3 else 4

    data = json.load(open(src, encoding="utf-8"))
    base = data.get("questions") or data.get("data") or []
    meta = data.get("meta") or {}

    out = []
    for k in range(copies):
        for q in base:
            item = dict(q)
            stem = (q.get("stem") or "").strip()
            if k:
                stem = f"{stem}（变体{k + 1}）"
            item["stem"] = stem
            out.append(item)

    bank = {
        "meta": {
            "title": f"{meta.get('title') or '题库'} · 压测版 {len(out)}题",
            "source": f"{meta.get('source') or src} + 压测复制 x{copies}",
            "count": len(out),
        },
        "questions": out,
    }
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, separators=(",", ":"))

    import os
    print(f"源 {len(base)} 题 x {copies} 份 -> {len(out)} 题")
    print(f"输出 {dst}  {os.path.getsize(dst):,} B")


if __name__ == "__main__":
    main()

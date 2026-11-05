"""把「题」目录批量解析出来的题库并进 gugu。

去重键与端上 TextNormalizer 同一套规则（NFKC + 小写 + 只留字母与 ASCII 数字），
所以「题干只差标点/空格/题号」的重复能被认出来。

用法：
    python merge_dir_bank.py [主库.json] [待并入.json] [输出.json]
默认：gugu-merged.json + 题-合并.json → gugu-merged.json（先备份）
"""
import json
import os
import shutil
import sys
import unicodedata

BASE = r"D:\openwork\abcdefg\题库导出"
MAIN = os.path.join(BASE, "gugu-merged.json")
ADD = os.path.join(BASE, "题-合并.json")
OUT = os.path.join(BASE, "gugu-merged.json")


def norm(text: str) -> str:
    text = unicodedata.normalize("NFKC", text or "").lower()
    return "".join(ch for ch in text if ch.isalpha() or (ch.isascii() and ch.isdigit()))


def main():
    global MAIN, ADD, OUT
    if len(sys.argv) > 1:
        MAIN = sys.argv[1]
    if len(sys.argv) > 2:
        ADD = sys.argv[2]
    if len(sys.argv) > 3:
        OUT = sys.argv[3]

    main_bank = json.load(open(MAIN, encoding="utf-8"))
    add_bank = json.load(open(ADD, encoding="utf-8"))

    questions = list(main_bank["questions"])
    seen = {norm(q["stem"]) for q in questions}
    added = 0
    dup = 0
    for q in add_bank["questions"]:
        key = norm(q["stem"])
        if not key:
            continue
        if key in seen:
            dup += 1
            continue
        seen.add(key)
        questions.append(q)
        added += 1

    subjects = {}
    for q in questions:
        subjects[q.get("subject", "")] = subjects.get(q.get("subject", ""), 0) + 1

    bank = {
        "meta": {
            "title": f"房地产估价师题库（{len(questions)}题）",
            "source": "估估练习 + 章节练习 PDF 批量解析（题目录）",
            "count": len(questions),
        },
        "questions": questions,
    }

    if os.path.abspath(OUT) == os.path.abspath(MAIN):
        backup = MAIN + ".bak"
        shutil.copy2(MAIN, backup)
        print(f"备份 {backup}")
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, separators=(",", ":"))

    print(f"主库 {len(main_bank['questions'])} 题 + 新增 {added} 题（跳过重复 {dup} 题）")
    print(f"合计 {len(questions)} 题")
    for name, n in sorted(subjects.items(), key=lambda kv: -kv[1]):
        print(f"  {n:5d}  {name or '(未分类)'}")
    print(f"输出 {OUT}  {os.path.getsize(OUT):,} B")


if __name__ == "__main__":
    main()

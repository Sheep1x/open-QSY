"""诊断 pdf_dir_bank.py 的切块与配对：看某份 PDF 的题目块/答案块数量与缺答案的题号。

用法：python pdf_dir_debug.py "文件名.pdf"
"""
import sys

from pypdf import PdfReader

from pdf_dir_bank import (
    clean_lines,
    parse_answers,
    parse_questions,
    split_answer,
    split_by_lecture,
    split_by_section,
)

BASE = r"D:\openwork\abcdefg\题库导出\题"


def main():
    name = sys.argv[1]
    reader = PdfReader(f"{BASE}\\{name}")
    text = "\n".join((p.extract_text() or "") for p in reader.pages)
    lines = clean_lines(text)
    print(f"{name}: 行 {len(lines)}")

    for lecture, chunk in split_by_lecture(lines):
        qpart, apart = split_answer(chunk)
        qblocks = split_by_section(qpart)
        ablocks = split_by_section(apart) if apart else [("", [])]
        print(f"\n讲 [{lecture or '-'}] 题区 {len(qpart)} 行 答区 {len(apart)} 行 题目块 {len(qblocks)} 答案块 {len(ablocks)}")
        for idx, (title, qlines) in enumerate(qblocks):
            alines = ablocks[idx][1] if idx < len(ablocks) else []
            qs = parse_questions(qlines)
            answers = parse_answers(alines)
            missing = [q["no"] for q in qs if q["no"] not in answers or not answers[q["no"]]["letters"] and not answers[q["no"]]["body"] and not answers[q["no"]]["explain"]]
            atitle = ablocks[idx][0] if idx < len(ablocks) else "(无)"
            print(f"  块{idx} 题[{title or '-'}] {len(qs)} 题 | 答[{atitle or '-'}] {len(answers)} 条 | 缺 {len(missing)} {missing[:12]}")
            if missing and idx < 2:
                sample = next((q for q in qs if q["no"] == missing[0]), None)
                if sample:
                    print(f"      缺样 {sample['no']}: {sample['stem'][:60]}")
                    nos = sorted(answers)[:8]
                    print(f"      答区题号 {nos} …")
                    for a in alines[:14]:
                        print(f"        | {a[:80]}")


if __name__ == "__main__":
    main()

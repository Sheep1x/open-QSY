"""批量探查「题」目录里 PDF 的结构：每份的小节标题、题号样例、答案区标记。

用法：python pdf_batch_probe.py [目录]
"""
import os
import re
import sys

from pypdf import PdfReader

BASE = sys.argv[1] if len(sys.argv) > 1 else r"D:\openwork\abcdefg\题库导出\题"

RE_BIG = re.compile(r"^[一二三四五六七八九十]+、\s*(.+)$")
RE_LECTURE = re.compile(r"^第\s*\d+\s*讲")
RE_Q = re.compile(r"^(\d+)[.、]\s*(.*)$")
RE_ANS = re.compile(r"^(\d+)[.、]\s*[【「]正确答案[】」]\s*(.*)$")


def clean(text: str) -> list[str]:
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if re.match(r"^第\s*\d+\s*页\s*(/\s*共\s*\d+\s*页)?$", line):
            continue
        if line.startswith("#DownID#"):
            continue
        if line.startswith("房地产估价师《房估实务》"):
            continue
        out.append(line)
    return out


def main():
    names = sorted(os.listdir(BASE))
    for name in names:
        if not name.lower().endswith(".pdf"):
            continue
        path = os.path.join(BASE, name)
        reader = PdfReader(path)
        text = "\n".join((p.extract_text() or "") for p in reader.pages)
        lines = clean(text)

        bigs = [m.group(1) for m in (RE_BIG.match(l) for l in lines) if m]
        lectures = [l for l in lines if RE_LECTURE.match(l)]
        qnos = [int(m.group(1)) for m in (RE_Q.match(l) for l in lines) if m]
        ansnos = [int(m.group(1)) for m in (RE_ANS.match(l) for l in lines) if m]
        cut = next(
            (i for i, l in enumerate(lines) if l in ("答案部分", "习题参考答案：", "习题参考答案") or l.startswith("习题参考答案")),
            -1,
        )
        opts = sum(1 for l in lines if re.match(r"^[A-F][.、．]", l))

        print("=" * 78)
        print(f"{name}  | 页 {len(reader.pages)} 行 {len(lines)} 字符 {len(text)}")
        print(f"  小题(答案标记) {len(ansnos)}  题号行 {len(qnos)}  选项行 {opts}  答案区行号 {cut}")
        print(f"  讲: {lectures[:3]}")
        print(f"  大节: {bigs[:8]}")
        if ansnos:
            print(f"  答案题号: {ansnos[:12]} … max={max(ansnos)}")
        # 答案区前两行样例
        if cut >= 0:
            print(f"  答案区样例: {lines[cut:cut + 3]}")


if __name__ == "__main__":
    main()

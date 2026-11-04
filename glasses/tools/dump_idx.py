#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读 qbank.idx，抽查 doc 记录，确认题干/答案/科目都非空。

用法：python dump_idx.py <qbank.idx路径>
"""
import struct
import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else "qbank.idx"
    b = open(path, "rb").read()
    if b[:4] != b"OQSY":
        print("不是 OQSY 索引")
        return 2

    ver, nq, nt, pb, tb, off_t, off_p, off_post, off_txt, off_docs, stride = \
        struct.unpack_from("<11I", b, 4)
    print(f"文件 {len(b):,} B  版本 {ver}  题目 {nq}  词条 {nt}  文本池 {tb:,} B  步长 {stride}")

    def s(off: int, ln: int) -> str:
        return b[off_txt + off: off_txt + off + ln].decode("utf-8", "replace")

    empty_ans = empty_stem = 0
    answers = {}
    picks = {0, 1, 2, nq // 4, nq // 2, nq - 2, nq - 1}

    for i in range(nq):
        o = off_docs + i * stride
        so, sl, ao, al, jo, jl = struct.unpack_from("<6I", b, o)
        stem, ans, subj = s(so, sl), s(ao, al), s(jo, jl)
        if not ans.strip():
            empty_ans += 1
        if not stem.strip():
            empty_stem += 1
        if i in picks:
            print(f"\n[{i}] 科目={subj}")
            print(f"  题干 {stem[:48]}")
            print(f"  答案 {ans[:40]!r}")

    print(f"\n空答案 {empty_ans} / {nq}    空题干 {empty_stem} / {nq}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    raise SystemExit(main())

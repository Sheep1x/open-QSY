#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_qbank.py — 把 PC 端题库 JSON 编译成**眼镜端可直接加载**的二进制索引包 qbank.idx

设计目标
--------
1. 纯离线、零依赖：眼镜端只需按固定格式读取字节，不需要 SQLite / 分词库 / 网络。
2. 内存友好：一次性 mmap/读入 ~1-3 MB 字节数组即可回答查询（6622 题实测约 1.1 MB）。
3. 抗 OCR 噪声：用「汉字二元组(bigram) 倒排 + IDF 加权覆盖率」打分，
   OCR 多字/漏字/乱码时仍能命中，优于整串模糊匹配。

用法
----
    python build_qbank.py --input ../题库合并.json --output ../app/app/src/main/assets/qbank.idx
    python build_qbank.py --input ../题库合并.json --report

文件格式（全部 little-endian）
------------------------------
header 64B:
   0  char[4] magic   = "OQSY"
   4  u32 version     = 1
   8  u32 nQuestions
  12  u32 nTerms
  16  u32 postingBytes
  20  u32 textBytes
  24  u32 offTerms      -> u32[nTerms]          已排序的 bigram key = (c1<<16)|c2
  28  u32 offTermPost   -> u32[nTerms+1]        每个 term 在 postings 区的起止偏移
  32  u32 offPostings   -> postings 字节块       varint(docId 差值) 序列
  36  u32 offTexts      -> 文本池(UTF-8)         stem/answer/subject 共用
  40  u32 offDocs       -> doc[nQuestions] * 28B
  44  u32 docStride     = 28
  48..63 reserved

doc 记录 28B:
   0  u32 stemOff    4  u32 stemLen
   8  u32 ansOff    12  u32 ansLen
  16  u32 subjOff   20  u32 subjLen
  24  u8  typeCode  25..27 pad
      typeCode: 1 单选 2 多选 3 判断 4 填空 5 简答 0 其他
"""

from __future__ import annotations

import argparse
import json
import math
import struct
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

MAGIC = b"OQSY"
VERSION = 1
ALPHA = 0.6            # 题干包含度权重（实测最优，见 docs/03）
HEADER_SIZE = 64
DOC_STRIDE = 28

TYPE_CODES = {
    "单选题": 1, "单选": 1,
    "多选题": 2, "多选": 2,
    "判断题": 3, "判断": 3,
    "填空题": 4, "填空": 4,
    "简答题": 5, "简答": 5,
}


# --------------------------------------------------------------------------
# 归一化：必须与眼镜端 Kotlin 实现逐条一致（quiz/TextNormalizer.kt）
# --------------------------------------------------------------------------
def normalize(text: str) -> str:
    """NFKC → 小写 → 只保留字母/数字/汉字，其余字符丢弃。"""
    if not text:
        return ""
    t = unicodedata.normalize("NFKC", text).lower()
    out = []
    for ch in t:
        # 与 Kotlin 侧严格等价：Character.isLetter(ch) || ch in '0'..'9'
        if ch.isalpha() or ("0" <= ch <= "9"):
            out.append(ch)
        # 标点、空白、符号全部丢弃
    return "".join(out)


def bigram_keys(norm: str) -> list[int]:
    """生成 u32 bigram key 序列；跳过代理对(BMP 之外的字符)。"""
    keys = []
    prev = -1
    for ch in norm:
        cp = ord(ch)
        if cp > 0xFFFF:
            prev = -1
            continue
        if prev >= 0:
            keys.append((prev << 16) | cp)
        prev = cp
    return keys


# --------------------------------------------------------------------------
# varint
# --------------------------------------------------------------------------
def write_varint(buf: bytearray, value: int) -> None:
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return


def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


# --------------------------------------------------------------------------
# 编译
# --------------------------------------------------------------------------
def compile_bank(questions: list[dict]) -> tuple[bytes, dict]:
    texts = bytearray()
    doc_records = bytearray()
    postings = defaultdict(list)          # term key -> [docId...]
    n_terms_missing = 0

    def add_text(s: str) -> tuple[int, int]:
        if not s:
            return 0, 0
        raw = s.encode("utf-8")
        off = len(texts)
        texts.extend(raw)
        return off, len(raw)

    for doc_id, q in enumerate(questions):
        stem = str(q.get("stem") or "").strip()
        ans = str(q.get("answerText") or q.get("answer") or "").strip()
        subj = str(q.get("subject") or "").strip()
        type_code = TYPE_CODES.get(str(q.get("type") or "").strip(), 0)

        if not stem:
            n_terms_missing += 1

        stem_off, stem_len = add_text(stem)
        ans_off, ans_len = add_text(ans)
        subj_off, subj_len = add_text(subj)

        doc_records.extend(struct.pack("<IIIIII", stem_off, stem_len,
                                       ans_off, ans_len,
                                       subj_off, subj_len))
        doc_records.extend(struct.pack("<BBH", type_code, 0, 0))
        # 只对题干建倒排（题干最稳定；选项/答案会引入噪声）
        for key in set(bigram_keys(normalize(stem))):
            postings[key].append(doc_id)

    term_keys = sorted(postings.keys())
    term_post = bytearray()
    term_post_offsets = [0]

    for key in term_keys:
        ids = postings[key]
        last = 0
        for i, doc_id in enumerate(ids):
            delta = doc_id - last if i else doc_id
            write_varint(term_post, delta)
            last = doc_id
        term_post_offsets.append(len(term_post))

    off_terms = HEADER_SIZE
    off_term_post = off_terms + 4 * len(term_keys)
    off_postings = off_term_post + 4 * len(term_post_offsets)
    off_texts = off_postings + len(term_post)
    off_docs = off_texts + len(texts)
    total = off_docs + len(doc_records)

    out = bytearray(total)
    struct.pack_into("<4sIIIIIIIIIIII", out, 0,
                     MAGIC, VERSION, len(questions), len(term_keys),
                     len(term_post), len(texts),
                     off_terms, off_term_post, off_postings, off_texts,
                     off_docs, DOC_STRIDE, 0)
    out[off_terms:off_term_post] = struct.pack(f"<{len(term_keys)}I", *term_keys) if term_keys else b""
    out[off_term_post:off_postings] = struct.pack(f"<{len(term_post_offsets)}I", *term_post_offsets)
    out[off_postings:off_texts] = term_post
    out[off_texts:off_docs] = texts
    out[off_docs:total] = doc_records

    stats = {
        "questions": len(questions),
        "terms": len(term_keys),
        "postings_bytes": len(term_post),
        "text_bytes": len(texts),
        "docs_bytes": len(doc_records),
        "total_bytes": total,
        "skipped_empty_stem": n_terms_missing,
    }
    return bytes(out), stats


# --------------------------------------------------------------------------
# 参考实现：模拟眼镜端读取 + 检索（用于验证格式与算法，不含任何 Python 特性）
# --------------------------------------------------------------------------
class BankReader:
    def __init__(self, blob: bytes):
        if blob[:4] != MAGIC:
            raise ValueError("不是 qbank.idx 文件")
        (self.version, self.n_docs, self.n_terms, self.posting_bytes,
         self.text_bytes, self.off_terms, self.off_term_post, self.off_postings,
         self.off_texts, self.off_docs, self.doc_stride, _) = struct.unpack_from("<12I", blob, 4)
        self.blob = blob
        self.n = self.n_docs or 1

    def term_offset(self, key: int) -> int:
        """二分查找 term key，返回 term 序号，未命中返回 -1。"""
        lo, hi = 0, self.n_terms - 1
        base = self.off_terms
        while lo <= hi:
            mid = (lo + hi) >> 1
            v = struct.unpack_from("<I", self.blob, base + mid * 4)[0]
            if v == key:
                return mid
            if v < key:
                lo = mid + 1
            else:
                hi = mid - 1
        return -1

    def postings(self, idx: int):
        start = struct.unpack_from("<I", self.blob, self.off_term_post + idx * 4)[0]
        end = struct.unpack_from("<I", self.blob, self.off_term_post + (idx + 1) * 4)[0]
        pos = self.off_postings + start
        limit = self.off_postings + end
        doc_id = 0
        first = True
        while pos < limit:
            delta, pos = read_varint(self.blob, pos)
            doc_id = delta if first else doc_id + delta
            first = False
            yield doc_id

    def doc_str(self, doc_id: int, field: int) -> str:
        """field: 0=stem 1=answer 2=subject"""
        base = self.off_docs + doc_id * self.doc_stride + field * 8
        off, ln = struct.unpack_from("<II", self.blob, base)
        if not ln:
            return ""
        return self.blob[self.off_texts + off: self.off_texts + off + ln].decode("utf-8", "ignore")

    def search(self, text: str, top_k: int = 40, alpha: float = ALPHA) -> list[dict]:
        """两段式打分（与眼镜端 quiz/QBankStore.kt 完全一致）：

        1) IDF 加权查询覆盖率 score1 = Σidf(命中项) / Σidf(全部查询项)
        2) 题干包含度 score2 = |题干bigram ∩ 查询bigram| / |题干bigram|
        final = (1-alpha)*score1 + alpha*score2,  alpha = 0.6
        """
        keys = set(bigram_keys(normalize(text)))
        if not keys:
            return []
        scores = defaultdict(float)
        total_idf = 0.0
        for key in keys:
            idx = self.term_offset(key)
            if idx < 0:
                continue
            hits = list(self.postings(idx))
            if not hits:
                continue
            df = len(hits)
            idf = math.log(1.0 + self.n / df)
            total_idf += idf
            for d in hits:
                scores[d] += idf
        if total_idf <= 0:
            return []

        ranked = sorted(scores.items(), key=lambda x: -x[1])[:top_k]
        out = []
        for doc_id, s in ranked:
            stem = self.doc_str(doc_id, 0)
            stem_keys = set(bigram_keys(normalize(stem)))
            cover = (len(stem_keys & keys) / len(stem_keys)) if stem_keys else 0.0
            final = (1.0 - alpha) * (s / total_idf) + alpha * cover
            out.append({
                "docId": doc_id,
                "score": final,
                "stem": stem,
                "answer": self.doc_str(doc_id, 1),
                "subject": self.doc_str(doc_id, 2),
            })
        out.sort(key=lambda x: -x["score"])
        return out


# --------------------------------------------------------------------------
# 自检：拿 100 条真题，模拟 OCR 噪声，看 top-1 命中率
# --------------------------------------------------------------------------
def self_test(reader: BankReader, questions: list[dict], sample: int = 150, seed: int = 7) -> None:
    """模拟「整张题卡入镜」：题干 + 选项 + OCR 丢字/噪声。"""
    import random
    rnd = random.Random(seed)
    picked = rnd.sample(range(len(questions)), min(sample, len(questions)))
    hit1 = hit3 = 0
    for doc_id in picked:
        q = questions[doc_id]
        stem = q["stem"] + "".join(
            f"{k}.{v}" for k, v in (q.get("options") or {}).items()
        )
        noisy = list(stem)
        # 模拟 OCR：随机丢字 8%、随机插入噪声 5%
        drop = max(1, len(noisy) // 14)
        for _ in range(drop):
            if noisy:
                noisy.pop(rnd.randrange(len(noisy)))
        inserted = 0
        for _ in range(len(stem) // 20):
            noisy.insert(rnd.randrange(len(noisy) + 1), rnd.choice("0123456789()，。甲乙丙丁ABCD"))
            inserted += 1
        query = "".join(noisy)
        res = reader.search(query, top_k=3)
        if res and res[0]["docId"] == doc_id:
            hit1 += 1
        if any(r["docId"] == doc_id for r in res):
            hit3 += 1
    print(f"自检(模拟 OCR 噪声, n={len(picked)}): top1={hit1 / len(picked):.1%}  top3={hit3 / len(picked):.1%}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default="../题库合并.json", help="题库 JSON（支持 {questions:[...]} 或 [...]）")
    ap.add_argument("--output", default="qbank.idx")
    ap.add_argument("--report", action="store_true", help="只打印统计，不写文件")
    ap.add_argument("--self-test", action="store_true", help="编译后跑噪声自检")
    args = ap.parse_args()

    raw = json.loads(Path(args.input).read_text(encoding="utf-8"))
    questions = raw["questions"] if isinstance(raw, dict) else raw
    if isinstance(raw, dict) and "meta" in raw:
        print(f"题库: {raw['meta'].get('title', '')} ({len(questions)} 题)")

    blob, stats = compile_bank(questions)
    for k, v in stats.items():
        print(f"  {k:>18}: {v:,}")

    if not args.report:
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(blob)
        print(f"写出 {out}  ({len(blob):,} bytes / {len(blob) / 1048576:.2f} MB)")

    if args.self_test:
        self_test(BankReader(blob), questions)
    return 0


if __name__ == "__main__":
    sys.exit(main())

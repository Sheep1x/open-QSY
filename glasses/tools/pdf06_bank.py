# -*- coding: utf-8 -*-
"""把 pdf06.txt（第六部分 习题模拟）解析成题库 JSON（与 gugu.json 同结构）。

结构：
  第01讲 … 一、不定项选择题 1-15   二、综合分析题 16-25   三、问答题 26-27
  第02讲 … 四、指错题 1            五、计算题 2-3
  第03讲 … （计算题 1-2）
  每讲末尾一段「习题参考答案」，题号从 1 重新开始。
"""
import json
import re
import io

SRC = r"D:\openwork\open-qsy\glasses\shots\pdf06.txt"
OUT = r"D:\openwork\open-qsy\glasses\shots\pdf06_bank.json"

SUBJECT = "房地产估价基础与实务"

RE_LECTURE = re.compile(r"^第\s*(\d+)\s*讲\s*(.+)$")
RE_BIG = re.compile(r"^([一二三四五六])、(.+)$")
RE_CASE = re.compile(r"^（([一二三四五六])）$")
# 题号行：「16、   1.题干」/「26、   （一）2020年…」都能吃，内嵌编号必须带小数点
RE_Q = re.compile(r"^(\d+)、\s*(?:(\d+)\.\s*)?(.*)$")
RE_OPT = re.compile(r"^([A-F])[.、．]\s*(.*)$")
RE_ANS = re.compile(r"^(\d+)、「正确答案」\s*(.*)$")
RE_SUP = re.compile(r"^(\d{1,4}(?:\.\d+)?)$")

ERR_LEAD = "下列房地产估价报告存在多处错误，请指明其中的10处。报告原文："


def join_lines(raw):
    """把 PDF 折行拼回去：消掉句中断行，保留题号/选项的分行。"""
    out = []
    for s in raw:
        s = s.strip()
        if not s:
            continue
        # 上标（m² / (1+5%)^0.75）单独成行，接回去
        if out and RE_SUP.match(s):
            num = s
            prev = out[-1]
            if prev.endswith("m") and num == "2":
                out[-1] = prev + "²"
            elif prev.endswith("m") and num == "3":
                out[-1] = prev + "³"
            else:
                out[-1] = prev + "^" + num
            continue
        out.append(s)
    return out


def load():
    with io.open(SRC, encoding="utf-8") as f:
        return [l.rstrip("\n") for l in f]


def split_lectures(lines):
    """按「第 N 讲」切成若干段，(标题, 行列表)。"""
    idx = [i for i, l in enumerate(lines) if RE_LECTURE.match(l.strip())]
    out = []
    for n, i in enumerate(idx):
        end = idx[n + 1] if n + 1 < len(idx) else len(lines)
        m = RE_LECTURE.match(lines[i].strip())
        out.append((f"第{m.group(1)}讲 {m.group(2).strip()}", lines[i + 1:end]))
    return out


def parse_lecture(title, body):
    """返回 (题目列表, 答案字典)。题目为 dict，答案 key 为题号。"""
    # 拆成「题目区 / 答案区」
    cut = next((i for i, l in enumerate(body) if l.strip().startswith("习题参考答案")), len(body))
    qpart, apart = body[:cut], body[cut + 1:]

    # ---------- 题目 ----------
    qpart = join_lines(qpart)
    questions = []
    big = ""          # 当前大题（不定项选择题 / 综合分析题 / …）
    big_note = ""     # 大题说明（共15题，每题1分…）
    material = ""     # 综合分析题的案例材料；指错题的整篇报告
    cur = None
    mode = None       # 'material' / 'stem' / 'opt'
    BIG_NAMES = ("不定项选择题", "综合分析题", "问答题", "指错题", "计算题")

    for l in qpart:
        s = l.strip()

        mb = RE_BIG.match(s)
        # 只认真正的题目大题——指错题的报告正文里也有「一、一般假设」这类编号
        if mb and any(n in mb.group(2) for n in BIG_NAMES):
            # 指错题整篇报告没有题号行，退出该大题时补一道
            if "指错题" in big and cur is None and material.strip():
                questions.append({
                    "no": 1, "inner": "", "big": big, "note": big_note,
                    "material": "", "stem": ERR_LEAD + material, "options": [],
                    "full": material,
                })
            big = next((n for n in BIG_NAMES if n in mb.group(2)), mb.group(2))
            big_note = s[s.find("（"):] if "（" in s else ""
            material = ""
            cur = None
            mode = "material" if big == "指错题" else None
            continue

        mc = RE_CASE.match(s)
        if mc and "指错题" not in big:
            material = ""
            cur = None
            mode = "material"
            continue

        mq = RE_Q.match(s)
        if mq and (mq.group(2) or mode != "material"):
            cur = {
                "no": int(mq.group(1)),
                "inner": mq.group(2) or "",
                "big": big,
                "note": big_note,
                "material": material,
                "stem": mq.group(3).strip(),
                "options": [],
            }
            questions.append(cur)
            mode = "stem"
            continue

        if mode == "material":
            # 大题说明的续行（「30 分。请在答题卡上作答…」）不属于正文
            if not material and big_note and (
                "答题卡" in s or "不计分" in s or re.match(r"^\d+\s*分", s)
            ):
                big_note += s
                continue
            material = (material + "\n" + s) if material else s
            continue
        if cur is None:
            continue

        mo = RE_OPT.match(s)
        if mo:
            cur["options"].append((mo.group(1), mo.group(2).strip()))
            mode = "opt"
            continue
        # 续行：题干跨行 / 选项文字跨行
        if mode == "opt" and cur["options"]:
            k, v = cur["options"][-1]
            cur["options"][-1] = (k, v + s)
        else:
            cur["stem"] += s

    # ---------- 答案 ----------
    apart = join_lines(apart)
    answers = {}
    cur = None
    for l in apart:
        s = l.strip()
        ma = RE_ANS.match(s)
        if ma:
            cur = {"letters": ma.group(2).strip(), "analysis": "", "body": ""}
            answers[int(ma.group(1))] = cur
            continue
        if cur is None:
            continue
        if s.startswith("「答案解析」"):
            cur["analysis"] += s[len("「答案解析」"):].strip()
        elif s.startswith("「正确答案」"):
            cur["letters"] += s[len("「正确答案」"):].strip()
        else:
            cur["body"] += s
    return questions, answers


def make_answer(q, ans):
    """选择题 → 「D. 原文」/多选换行；主观题 → 正文。"""
    if not ans:
        return ""
    letters = re.sub(r"[^A-F]", "", ans["letters"].upper())
    if letters and q["options"]:
        opt = dict(q["options"])
        parts = []
        for ch in sorted(set(letters)):
            txt = opt.get(ch)
            parts.append(f"{ch}. {txt}" if txt else ch)
        return "\n".join(parts)
    # 主观题：首段和「正确答案」挤在同一行，后面才是正文，两段要接起来
    head = (ans["letters"] or "").strip()
    body = (ans["body"] or "").strip()
    if head and body:
        return head + "\n" + body
    return head or body


def make_analysis(q, ans):
    if not ans and not q.get("full"):
        return ""
    seg = []
    if q.get("material"):
        seg.append("【案例材料】" + q["material"])
    if q.get("full"):
        seg.append("【报告原文】" + q["full"])
    if ans:
        # 「答案解析」的首行在前，续行在后——原先反过来，导致表格跑到文字之前
        if (ans["analysis"] or "").strip():
            seg.append(ans["analysis"].strip())
        if (ans["body"] or "").strip():
            seg.append(ans["body"].strip())
    return "\n".join(seg)


def main():
    lines = load()
    out = []
    seq = 0
    for title, body in split_lectures(lines):
        qs, ans = parse_lecture(title, body)
        print(f"\n== {title}: {len(qs)} 题, 答案 {len(ans)} 条")
        for q in qs:
            seq += 1
            a = ans.get(q["no"])
            answer = make_answer(q, a)
            stem = q["stem"].strip()
            # 指错题：题干是整篇报告，索引只需要开头一段，全文进解析
            if q.get("full"):
                stem = ERR_LEAD + q["full"][:700].replace("\n", " ") + "……（全文见解析）"
            qtype = q["big"] or "计算题"
            out.append({
                "id": f"pdf06-{seq:03d}",
                "stem": stem,
                "answer": answer,
                "subject": SUBJECT,
                "type": qtype,
                "chapter": f"第六部分 习题模拟 · {title}",
                "analysis": make_analysis(q, a),
            })
            flag = "" if answer else "  <<< 无答案"
            print(f"  {q['no']:2d} [{q['big'][:8]}] {stem[:36]} | {answer[:28]!r}{flag}")

    meta = {
        "title": f"房地产估价师《房估实务》习题模拟（{len(out)}题）",
        "source": "《房地产估价师《房估实务》第六部分 习题模拟_答案分离.pdf》",
        "count": len(out),
        "fields": "id,stem,answer,subject,type,chapter,analysis",
    }
    from pdf06_tables import fix_tables
    bank = {"meta": meta, "questions": out}
    n_tbl = fix_tables(bank)
    print(f"\n表格结构化整理: {n_tbl} 处")
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, indent=1)
    print(f"\n写出 {OUT}  共 {len(out)} 题")
    no_ans = [x for x in out if not x["answer"]]
    print("无答案题数:", len(no_ans))
    no_opt = [x for x in out if x["type"].endswith("选择题") and x["answer"] and "." not in x["answer"]]
    print("选择题答案未带选项原文:", len(no_opt))
    for x in no_opt[:5]:
        print("   ", x["id"], x["stem"][:30], "|", x["answer"])


if __name__ == "__main__":
    main()

"""把「题」目录下的整批 PDF（题目答案分离）解析成题库。

目录里有两套排版，脚本都要吃下：

A 套（`NN-第X章　xxx-题目答案分离.pdf`，章节式）
    一、单选题 / 二、多选题 / 三、阅读理解 …
    `1.题干（　）。` `A.选项`
    答案区以「答案部分」开头，答案写作 `1. 【正确答案】 A`，解析 `【答案解析】…`

B 套（`NN-第X部分　xxx_答案分离.pdf`、`NN-第X章　xxx_答案分离.pdf`，讲义式）
    `第 01 讲…` 分讲，题号逐讲重置
    `1、  【例题】题干` `A.选项`
    答案区以「习题参考答案：」开头，答案写作 `1、「正确答案」C`，解析 `「答案解析」…`

难点都在「题号会在每讲/每节重置」，所以配对顺序是：
    按讲切（若有）→ 每讲内按答案标记切成题区/答区 → 再按大节切 → 顺序配对 → 题号查字典

用法：
    python pdf_dir_bank.py [目录] [输出.json]
"""
import json
import os
import re
import sys

from pypdf import PdfReader

RE_PAGE = re.compile(r"^第\s*\d+\s*页\s*(/\s*共\s*\d+\s*页)?$")
RE_LECTURE = re.compile(r"^第\s*(\d+)\s*讲\s*(.*)$")
RE_SECTION = re.compile(r"^([一二三四五六七八九十]+)、\s*(.+)$")
RE_Q = re.compile(r"^(\d+)[.、]\s*(.*)$")
RE_Q_MARK = re.compile(r"^(\d+)[.、]\s*【")
RE_SUBQ = re.compile(r"[（(]\s*(\d)\s*[）)]")
RE_OPT = re.compile(r"^([A-F])[.、．]\s*(.*)$")
RE_ANS_ANY = re.compile(
    r"^(\d+)[.、]\s*(?:[（(]\s*(\d+)\s*[）)]\s*)*[【「]([^】」]{0,12}?(?:答案|解析|解答)[^】」]{0,8})[】」]\s*(.*)$"
)
RE_HEAD = re.compile(r"^[【「](正确答案|答案解析|答案|解析)[】」]\s*(.*)$")

ANSWER_MARKS = ("答案部分", "习题参考答案", "参考答案")

# 教材 PDF 里夹带的广告水印（页脚整行 / 拼在正文行尾），不去掉会混进题干和选项
RE_AD1 = re.compile(r"初级、中级、注会、税务师")
RE_AD2 = re.compile(r"\s*多网校课件[^。]*。?")

# 这份已经由 pdf06_bank.py 单独解析（含表格结构化整理）并合入 gugu，别再重复吃一遍
SKIP_FILES = ("06-第六部分　习题模拟_答案分离.pdf",)
TYPE_WORDS = (
    "单选题", "多选题", "不定项选择题", "单项选择题", "多项选择题", "判断题",
    "简答题", "问答题", "计算题", "案例分析", "综合分析", "阅读理解", "指错题", "案例题",
)

# 科目判定：靠章节名词，不靠文件名前缀（「06-第六章」在两套书里各有一章）
SUBJECT_RULES = [
    ("土地估价基础与实务", ("土地估价基本方法", "公示地价修正法", "价值损失评估", "其他估价方法", "保存估价资料", "土地")),
    ("房地产估价基础与实务", ("第一部分", "第二部分", "第三部分", "第四部分", "第五部分", "第六部分", "习题模拟", "不定项选择题", "问答题专题", "综合分析题专题", "计算题专题", "指错题专题")),
    ("房地产开发经营与管理", ("建筑基础知识", "房地产开发经营过程", "房地产市场调查", "投资现金流", "项目经济评价", "不同类型房地产估价", "不同目的房地产估价", "估价文书写作", "报告评析")),
    ("房地产估价原理与方法", ("估价概论", "估价目的", "估价对象", "价值和价格概述", "价值影响因素", "估价原则", "市场比较法", "收益法", "成本法", "假设开发法", "估价程序")),
]


def subject_of(filename: str) -> str:
    for name, keys in SUBJECT_RULES:
        if any(k in filename for k in keys):
            return name
    return "房地产估价综合"


def clean_lines(text: str) -> list[str]:
    out = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        line = RE_AD2.sub("", line).strip()
        if not line:
            continue
        if RE_PAGE.match(line):
            continue
        if line.startswith("#DownID#"):
            continue
        if line.startswith("房地产估价师《房估实务》"):
            continue
        if RE_AD1.match(line):
            continue
        out.append(line)
    return out


def join_lines(lines: list[str]) -> str:
    """把一段碎行合成文本：中文之间直接拼，英文/数字之间补空格。"""
    buf = ""
    for line in lines:
        piece = line.strip()
        if not piece:
            continue
        if not buf:
            buf = piece
            continue
        prev, nxt = buf[-1], piece[0]
        if prev.isascii() and nxt.isascii() and (prev.isalnum() or prev in "%.)") and nxt.isalnum():
            buf += " " + piece
        else:
            buf += piece
    return buf.strip()


def split_by_lecture(lines: list[str]) -> list[tuple[str, list[str]]]:
    marks = [(i, RE_LECTURE.match(l)) for i, l in enumerate(lines) if RE_LECTURE.match(l)]
    if not marks:
        return [("", lines)]
    out = []
    for idx, (start, m) in enumerate(marks):
        end = marks[idx + 1][0] if idx + 1 < len(marks) else len(lines)
        out.append((m.group(0), lines[start:end]))
    return out


def split_answer(lines: list[str]) -> tuple[list[str], list[str]]:
    for i, line in enumerate(lines):
        if any(line.startswith(m) for m in ANSWER_MARKS):
            return lines[:i], lines[i + 1:]
    return lines, []


def split_by_section(lines: list[str]) -> list[tuple[str, list[str]]]:
    marks = []
    for i, line in enumerate(lines):
        m = RE_SECTION.match(line)
        if m and any(w in m.group(2) for w in TYPE_WORDS):
            marks.append((i, m.group(2)))
    if not marks:
        return [("", lines)]
    out = []
    for idx, (start, title) in enumerate(marks):
        end = marks[idx + 1][0] if idx + 1 < len(marks) else len(lines)
        out.append((title, lines[start:end]))
    return out


def _text_target(cur: dict) -> list[str]:
    return cur["subs"][-1]["text"] if cur["subs"] else cur["material"]


def _feed_text(cur: dict, text: str) -> None:
    """按「（1）（2）…」把一段文字切开：材料进 material，子问进 subs。

    材料题的排版是「一大段材料 + （1）子问 + 选项 + （2）子问 + 选项…」，
    不切开的话四个小问会被并成一道，答案只剩最后一个。
    """
    pos = 0
    while pos < len(text):
        m = RE_SUBQ.search(text, pos)
        if not m:
            _text_target(cur).append(text[pos:])
            return
        k = int(m.group(1))
        if k == len(cur["subs"]) + 1:
            if m.start() > pos:
                _text_target(cur).append(text[pos:m.start()])
            cur["subs"].append({"text": [], "options": []})
            cur["opt_open"] = None
            pos = m.end()
        else:
            _text_target(cur).append(text[pos:m.end()])
            pos = m.end()


def _expand(item: dict) -> list[dict]:
    """把解析出来的「一道大题」摊平成若干道可直接入库的题。"""
    material = join_lines(item["material"])
    subs = item["subs"]
    if not subs:
        return [{"no": item["no"], "sub": 0, "stem": material, "options": []}]
    if len(subs) == 1 and not join_lines(subs[0]["text"]):
        return [{"no": item["no"], "sub": 0, "stem": material, "options": subs[0]["options"]}]
    if all(not sub["options"] for sub in subs):
        # 一段材料里用「（1）（2）（3）」分条陈述的，还是同一道题，不能摊成三道
        whole = join_lines([material] + [join_lines(sub["text"]) for sub in subs])
        return [{"no": item["no"], "sub": 0, "stem": whole, "options": []}]
    out = []
    for idx, sub in enumerate(subs, 1):
        stem = join_lines([material, join_lines(sub["text"])])
        out.append({"no": item["no"], "sub": idx, "stem": stem, "options": sub["options"]})
    return out


def _next_subq_cut(cur: dict, text: str) -> int | None:
    """这一行里是否藏着「下一个子问」的开头（`D.xxxx（2）yyyy` 这种）。

    PDF 抽出来的选项和子问经常挤在同一行，只按整行判断角色就会把
    （2）（3）（4）全塞进第一个子问的选项 D 里。
    """
    expect = len(cur["subs"]) + 1
    for m in RE_SUBQ.finditer(text):
        if int(m.group(1)) == expect:
            return m.start()
    return None


def _append_option(cur: dict, key: str, text: str) -> None:
    if not cur["subs"]:
        cur["subs"].append({"text": [], "options": []})
    sub = cur["subs"][-1]
    sub["options"].append([key, text])
    cur["opt_open"] = len(sub["options"]) - 1


def parse_questions(lines: list[str]) -> list[dict]:
    """题号必须连续递增才算新题：报告正文/计算过程里「2026.77m」这类行首数字不能被当题号。

    若这一块里存在 `1.【指错题】` 这种带题型标记的题号行（整篇估价报告就是一道题），
    就只认这种行——否则报告正文里自己的「1. 2. 3.」编号会把一道题拆成几十道。
    """
    marker_mode = any(RE_Q_MARK.match(l) for l in lines)
    items = []
    cur = None
    expect = None
    for line in lines:
        m = RE_Q.match(line)
        if m:
            no = int(m.group(1))
            if marker_mode and not RE_Q_MARK.match(line):
                m = None  # 报告正文的编号，当正文
            elif cur is None or no == expect:
                if cur:
                    items.append(cur)
                cur = {"no": no, "material": [], "subs": [], "opt_open": None}
                expect = no + 1
                _feed_text(cur, m.group(2))
                continue
        if cur is None:
            continue
        mo = RE_OPT.match(line)
        if mo:
            cut = _next_subq_cut(cur, line)
            if cut is None:
                _append_option(cur, mo.group(1), mo.group(2))
            else:
                _append_option(cur, mo.group(1), line[mo.start(1):cut])
                _feed_text(cur, line[cut:])
            continue
        if cur["subs"] and cur["opt_open"] is not None:
            cut = _next_subq_cut(cur, line)
            if cut is None:
                cur["subs"][-1]["options"][cur["opt_open"]][1] += line
            else:
                cur["subs"][-1]["options"][cur["opt_open"]][1] += line[:cut]
                _feed_text(cur, line[cut:])
        else:
            _feed_text(cur, line)
    if cur:
        items.append(cur)

    out = []
    for it in items:
        for q in _expand(it):
            if not q["stem"]:
                continue
            q["options"] = [(k, v.strip()) for k, v in q["options"] if v.strip()]
            out.append(q)
    return out


def parse_answers(lines: list[str]) -> dict[tuple[int, int], dict]:
    """只认带标记的答案起点（`1. 【正确答案】` / `1、「答案解析」`）。

    答案正文里自带的 `1. 2. 3.` 编号不能当新答案的起点——问答题、计算题的解答
    就是这么一段段编号写的，一当新答案就会把真正的那条答案覆盖掉。

    键是 (题号, 小问号)，材料题的答案写作 `24.（1）【正确答案】A`，小问号缺省为 0。
    """
    answers = {}
    cur = None
    mode = None  # "answer" | "explain"

    for line in lines:
        m = RE_ANS_ANY.match(line)
        if m:
            key = (int(m.group(1)), int(m.group(2) or 0))
            tag, rest = m.group(3), m.group(4)
            if "正确答案" in tag:
                cur = {"letters": rest.strip(), "body": [], "explain": []}
                answers[key] = cur
                mode = "answer"
            else:
                cur = answers.setdefault(key, {"letters": "", "body": [], "explain": []})
                if rest.strip():
                    cur["explain"].append(rest.strip())
                mode = "explain"
            continue
        mh = RE_HEAD.match(line)
        if mh:
            if cur is not None:
                cur["explain"].append(mh.group(2))
            mode = "explain"
            continue
        if cur is None:
            continue
        cur["explain" if mode == "explain" else "body"].append(line)
    return answers


def render_answer(letters: str, body: list[str], explain: list[str], options: list[tuple[str, str]]) -> str:
    raw = re.sub(r"[^A-F]", "", letters.upper())
    if raw:
        picked = []
        opt_map = {k: v for k, v in options}
        for letter in sorted(set(raw)):
            text = opt_map.get(letter, "")
            picked.append(f"{letter}. {text}" if text else letter)
        return "\n".join(picked)
    text = join_lines(body) if body else join_lines(explain)
    return text


ZHI_CUO_LEAD = "下列房地产估价报告存在多处错误，请指出其中的错误之处。"


def parse_report_chapter(chunk: list[str], subject: str, book: str) -> list[dict]:
    """指错题专题：整篇估价报告就是一道题，答案散在正文里以「●」标注。"""
    errs = [l[1:].strip() for l in chunk if l.startswith("●") and l[1:].strip()]
    if not errs:
        return []
    body = " ".join(l for l in chunk if not l.startswith("●"))
    stem = f"{ZHI_CUO_LEAD}\n{body[:700]}……（全文见解析／题库仅存题干摘要，作答需纸质报告）"
    return [
        {
            "stem": stem,
            "answer": "\n".join(f"{i}. {e}" for i, e in enumerate(errs, 1)),
            "options": [],
            "subject": subject,
            "type": "指错题",
            "source": book,
            "no": 1,
        }
    ]


def parse_pdf(path: str) -> list[dict]:
    reader = PdfReader(path)
    text = "\n".join((p.extract_text() or "") for p in reader.pages)
    lines = clean_lines(text)
    subject = subject_of(os.path.basename(path))
    book = re.sub(r"^\d+-", "", os.path.splitext(os.path.basename(path))[0])

    out = []
    for lecture, chunk in split_by_lecture(lines):
        qpart, apart = split_answer(chunk)
        if not apart:
            out.extend(parse_report_chapter(chunk, subject, book))
            continue
        # 答案区的题号在整份（讲）内连续，不按大节重置，所以整体解析、各题块共用一个字典
        answers = parse_answers(apart)
        for title, qlines in split_by_section(qpart):
            for q in parse_questions(qlines):
                a = (
                    answers.get((q["no"], q["sub"]))
                    or answers.get((q["no"], 0))
                    or (answers.get((q["no"], 1)) if q["sub"] else None)
                )
                if a is not None:
                    answer = render_answer(a["letters"], a["body"], a["explain"], q["options"])
                else:
                    # 被合并回一道的题，答案区仍按子问分段，逐条列出来
                    parts = []
                    for s in sorted(k[1] for k in answers if k[0] == q["no"] and k[1]):
                        sa = answers[(q["no"], s)]
                        text = render_answer(sa["letters"], sa["body"], sa["explain"], []).strip()
                        if text:
                            parts.append(f"（{s}）{text}")
                    answer = "\n".join(parts)
                if not answer.strip() and not q["options"] and not re.search(r"[。？]", q["stem"]):
                    # 现金流量表之类的表格行会被当成题干，它们既没有选项也没有答案、也不成句
                    continue
                out.append(
                    {
                        "stem": q["stem"],
                        "answer": answer,
                        "options": [{"key": k, "text": v} for k, v in q["options"]],
                        "subject": subject,
                        "type": title or (lecture or ""),
                        "source": book,
                        "no": q["no"],
                    }
                )
    return out


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else r"D:\openwork\abcdefg\题库导出\题"
    dst = sys.argv[2] if len(sys.argv) > 2 else r"D:\openwork\abcdefg\题库导出\题-合并.json"

    all_q = []
    report = []
    for name in sorted(os.listdir(base)):
        if not name.lower().endswith(".pdf"):
            continue
        if name in SKIP_FILES:
            print(f"{'—':>5} 跳过（已单独解析）  {name}")
            continue
        qs = parse_pdf(os.path.join(base, name))
        empty = sum(1 for q in qs if not q["answer"].strip())
        report.append((name, len(qs), empty))
        all_q.extend(qs)

    for name, n, empty in report:
        flag = "  ← 缺答案" if empty else ""
        print(f"{n:5d} 题  空答案 {empty:3d}   {name}{flag}")
    print(f"\n合计 {len(all_q)} 题，空答案 {sum(1 for q in all_q if not q['answer'].strip())} 题")

    bank = {
        "meta": {
            "title": f"房地产估价师题库·章节练习（{len(all_q)}题）",
            "source": "题目录 PDF 批量解析",
            "count": len(all_q),
        },
        "questions": all_q,
    }
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(bank, f, ensure_ascii=False, separators=(",", ":"))
    print(f"输出 {dst}  {os.path.getsize(dst):,} B")


if __name__ == "__main__":
    main()

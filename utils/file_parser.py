import logging
import re
from pathlib import Path

import pandas as pd

logger = logging.getLogger(__name__)


def _has_header(filepath: str, ext: str) -> bool:
    try:
        if ext == ".csv":
            df_no_header = pd.read_csv(filepath, header=None, nrows=5, dtype=str)
            df_header = pd.read_csv(filepath, nrows=5, dtype=str)
        else:
            df_no_header = pd.read_excel(filepath, header=None, nrows=5, dtype=str)
            df_header = pd.read_excel(filepath, nrows=5, dtype=str)

        first_row = df_no_header.iloc[0].astype(str).str.strip()
        second_row = df_no_header.iloc[1].astype(str).str.strip() if len(df_no_header) > 1 else None

        header_keywords = {"题目", "问题", "题干", "答案", "正确答案", "question", "answer", "选项", "解析", "类型"}
        for val in first_row:
            if val.lower() in header_keywords:
                return True

        if second_row is not None and len(first_row) >= 2:
            first_avg = first_row.str.len().mean()
            second_avg = second_row.str.len().mean()
            if first_avg < 5 and second_avg > first_avg * 2:
                return True

        return False
    except Exception:
        return True


def _guess_column(columns: list[str], keywords: list[str]) -> str | None:
    for col in columns:
        lower = str(col).lower().strip()
        for kw in keywords:
            if kw in lower:
                return col
    return None


def parse_docx(filepath: str) -> list[dict]:
    from docx import Document

    doc = Document(filepath)
    items = []
    current_question = None
    current_options = {}

    for p in doc.paragraphs:
        text = p.text.strip().replace('\xa0', ' ')
        if not text:
            continue

        # Skip 组合题 background
        if re.match(r'^\d+、\[组合题\]', text):
            continue

        # Question line
        m = re.match(r'^\d+、\[(组合题)?([单多]选题)\]', text)
        if m:
            # Finalize previous question's options
            if current_question and current_options:
                current_question["options"] = current_options
                parts = [current_question["question"]]
                for key in sorted(current_options.keys()):
                    parts.append(f"{key} {current_options[key]}")
                current_question["question_full"] = " ".join(parts)
            q_text = text[m.end():].strip()
            current_question = {"question": q_text, "answer": ""}
            current_options = {}
            items.append(current_question)
            continue

        # Option line: A、xxx
        m = re.match(r'^([A-E])[、.〕）)]\s*(.+)', text)
        if m and current_question is not None:
            current_options[m.group(1)] = m.group(2).strip()
            continue

        # Answer line
        m = re.match(r'^答案[：:]\s*(.+)', text)
        if m and current_question is not None:
            current_question["answer"] = m.group(1).strip()
            continue

        # Skip 难度 lines
        if re.match(r'^难度[：:]', text):
            continue

    # Finalize last question's options
    if current_question and current_options:
        current_question["options"] = current_options
        parts = [current_question["question"]]
        for key in sorted(current_options.keys()):
            parts.append(f"{key} {current_options[key]}")
        current_question["question_full"] = " ".join(parts)

    logger.info("Parsed %d items from %s (docx)", len(items), filepath)
    return items


def _guess_option_columns(columns: list[str]) -> dict[str, str]:
    """Map A-E to their column names. Returns {A: col_name, B: col_name, ...}."""
    option_map = {}
    # Patterns: 选项A, A, optA, option_a, 选A, A选项
    # NOTE: column names are lowered before matching, so use [a-e] not [A-E]
    patterns = [
        (r'^选项\s*([a-e])$', 1),
        (r'^选\s*([a-e])$', 1),
        (r'^([a-e])\s*选项$', 1),
        (r'^([a-e])$', 1),
        (r'^opt\s*([a-e])$', 1),
        (r'^option_?([a-e])$', 1),
    ]
    for col in columns:
        lower = str(col).lower().strip()
        for pat, group in patterns:
            m = re.match(pat, lower)
            if m:
                key = m.group(group).upper()
                if key not in option_map:
                    option_map[key] = col
                break
    return option_map


def parse_file(filepath: str, question_col: str = "", answer_col: str = "") -> list[dict]:
    path = Path(filepath)
    ext = path.suffix.lower()

    if ext == ".docx":
        return parse_docx(filepath)

    if ext not in (".csv", ".xlsx", ".xls"):
        raise ValueError(f"不支持的文件格式: {ext}")

    has_header = _has_header(filepath, ext)

    if ext == ".csv":
        if has_header:
            df = pd.read_csv(filepath, dtype=str, keep_default_na=False)
        else:
            df = pd.read_csv(filepath, header=None, dtype=str, keep_default_na=False)
            df.columns = [f"col_{i}" for i in range(len(df.columns))]
    else:
        if has_header:
            df = pd.read_excel(filepath, dtype=str, keep_default_na=False)
        else:
            df = pd.read_excel(filepath, header=None, dtype=str, keep_default_na=False)
            df.columns = [f"col_{i}" for i in range(len(df.columns))]

    df = df.fillna("")
    columns = list(df.columns)

    q_col = question_col if question_col and question_col in columns else _guess_column(columns, ["题目", "问题", "题干", "question"])
    a_col = answer_col if answer_col and answer_col in columns else _guess_column(columns, ["答案", "正确答案", "answer", "答"])
    option_cols = _guess_option_columns(columns)

    if not q_col and len(columns) >= 1:
        q_col = columns[0]
    if not a_col and len(columns) >= 2:
        a_col = columns[1]

    if not q_col:
        raise ValueError("无法识别题目列")

    items = []
    for _, row in df.iterrows():
        q = str(row[q_col]).strip()
        if not q:
            continue
        item = {"question": q}
        if a_col and a_col in row.index:
            item["answer"] = str(row[a_col]).strip()
        else:
            item["answer"] = ""
        # Read options if columns exist
        options = {}
        for key, col in option_cols.items():
            if col in row.index:
                val = str(row[col]).strip()
                if val:
                    options[key] = val
        if options:
            item["options"] = options
        # Auto-generate question_full (stem + options) for dual-column search
        parts = [q]
        for key in sorted(options.keys()):
            parts.append(f"{key} {options[key]}")
        item["question_full"] = " ".join(parts)
        items.append(item)

    logger.info("Parsed %d items from %s (header=%s, q=%s, a=%s, opts=%s)", len(items), filepath, has_header, q_col, a_col, list(option_cols.keys()))
    return items


def detect_columns(filepath: str) -> list[str]:
    path = Path(filepath)
    ext = path.suffix.lower()

    if ext == ".docx":
        return ["自动解析（docx无需选列）"]

    kwargs = dict(nrows=0, dtype=str)
    if ext == ".csv":
        df = pd.read_csv(filepath, **kwargs)
    elif ext in (".xlsx", ".xls"):
        df = pd.read_excel(filepath, **kwargs)
    else:
        return []
    cols = list(df.columns)
    if all(str(c).startswith("Unnamed") for c in cols):
        cols = [f"A列", "B列", "C列", "D列"][: len(cols)]
    return cols

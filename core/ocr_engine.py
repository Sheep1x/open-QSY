"""Local OCR engine: RapidOCR → text parser → GPT Vision fallback.

Returns structured result compatible with VisionEngine.recognize() dict format:
    {question, options, type, selected} or None for GPT Vision fallback.

NOTE: Windows OCR (WinRT) is available as a secondary tier but disabled due to
complex dependency chain (requires winrt storage.streams + foundation submodules).
Can be re-enabled if those dependencies are installed. See: _init_win_ocr stub.
"""
import logging
import re

import cv2
import numpy as np

logger = logging.getLogger(__name__)


# ── Text parser ──────────────────────────────────────────────────────

# Remove radio/checkbox symbols that appear before option letters
_OPTION_PREFIX_RE = re.compile(
    r'^[○◯●◉◎⚪⚫□■☐☑☒▢✓✔✗✘√×.\-]+\s*',
    re.UNICODE,
)

_OPTION_RE = re.compile(r'^([A-E])[.、\)）\s]\s*(.+)$', re.UNICODE)

# Strip leading serial numbers / question-type tags from OCR text
_SERIAL_RE = re.compile(
    r'^\s*('
    r'\d+[\.、\s]+'              # 27.  27、
    r'|第\s*\d+\s*题\s*[\.、]?\s*'  # 第27题  第27题.
    r'|\[\s*(单选|多选|判断)[题\s]*\]\s*'  # [单选题] [多选]
    r')+',
    re.UNICODE,
)


def _clean_question(text: str) -> str:
    """Remove serial numbers and normalize whitespace."""
    text = _SERIAL_RE.sub('', text)
    text = re.sub(r'\s+', ' ', text)
    return text.strip()


def _merge_option_lines(lines: list[str]) -> list[str]:
    """Merge single-letter A-E lines with the next line (option content)."""
    merged = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # Single letter A-E (possibly with spaces around) followed by content on next line
        if re.match(r'^[A-E]\s*$', line) and i + 1 < len(lines):
            next_line = lines[i + 1].strip()
            # Conservative: don't merge if next line is too long or looks like question text
            if len(next_line) > 15 or '。' in next_line or '？' in next_line or '?' in next_line:
                merged.append(line)
                i += 1
            else:
                merged.append(line + " " + next_line)
                i += 2
        else:
            merged.append(line)
            i += 1
    return merged


def _detect_unmarked_options(lines: list[str]) -> tuple[list[str], list[tuple[str, str]]]:
    """Detect options without A-E prefix using heuristics."""
    if len(lines) < 3:
        return lines, []

    # Strategy 1: Look for a line ending with punctuation that marks question end
    split_idx = -1
    end_marks = ('（）', '()', '（ ）', '( )', '？', '?', '。', '.')
    for i, line in enumerate(lines):
        if line.endswith(end_marks):
            if i + 2 < len(lines):
                split_idx = i + 1
                break

    if split_idx > 0 and split_idx < len(lines):
        question_lines = lines[:split_idx]
        candidate_opts = lines[split_idx:]
        if 2 <= len(candidate_opts) <= 6:
            q_avg_len = sum(len(l) for l in question_lines) / len(question_lines)
            opt_lens = [len(l) for l in candidate_opts]
            opt_avg = sum(opt_lens) / len(opt_lens)
            if opt_avg <= q_avg_len * 0.75:
                option_lines = [(chr(ord('A') + j), candidate_opts[j]) for j in range(len(candidate_opts))]
                return question_lines, option_lines

    # Strategy 2: Look for a "cliff" where lines suddenly become short and uniform
    if len(lines) >= 4:
        lengths = [len(l) for l in lines]
        for i in range(2, len(lines) - 1):
            q_avg = sum(lengths[:i]) / i
            remaining = lines[i:]
            rem_lens = lengths[i:]
            rem_avg = sum(rem_lens) / len(rem_lens)
            if rem_avg <= q_avg * 0.6 and max(rem_lens) - min(rem_lens) <= max(rem_lens) * 0.5:
                if 2 <= len(remaining) <= 6:
                    option_lines = [(chr(ord('A') + j), remaining[j]) for j in range(len(remaining))]
                    return lines[:i], option_lines

    return lines, []


def _parse_text(text: str) -> dict | None:
    """Parse raw OCR text into structured {question, options, type, selected}."""
    lines = [l.strip() for l in text.strip().split('\n') if l.strip()]
    if not lines:
        return None

    # Merge single-letter A-E lines with next line (common in UI options)
    lines = _merge_option_lines(lines)
    # Strip radio/checkbox symbols before option letters
    lines = [_OPTION_PREFIX_RE.sub('', line) for line in lines]

    result = {"question": "", "options": {}, "type": "", "selected": []}
    question_lines = []
    option_lines = []
    in_options = False

    for line in lines:
        m = _OPTION_RE.match(line)
        if m:
            in_options = True
            key, val = m.group(1), m.group(2).strip()
            option_lines.append((key, val))
        elif in_options and option_lines:
            # Continuation of previous option (multi-line options)
            k, v = option_lines[-1]
            option_lines[-1] = (k, v + " " + line)
        else:
            question_lines.append(line)

    # Fallback: detect unmarked options (no A/B/C prefix)
    if not option_lines and len(lines) >= 3:
        question_lines, option_lines = _detect_unmarked_options(lines)

    raw_question = "\n".join(question_lines).strip()
    result["question"] = _clean_question(raw_question)
    result["options"] = {k: v for k, v in option_lines}

    # Determine type based on option count & content
    n = len(result["options"])
    if n == 2:
        vals = [result["options"][k].strip().lower() for k in result["options"]]
        judge_words = {"正确", "对", "√", "错误", "错", "×", "right", "wrong", "true", "false"}
        result["type"] = "判断" if any(v in judge_words for v in vals) else "单选"
    elif 3 <= n <= 4:
        result["type"] = "单选"
    elif n > 4:
        result["type"] = "多选"

    if result["question"] or result["options"]:
        return result
    return None


# ── OCR Engine ────────────────────────────────────────────────────────


class OcrEngine:
    """Local OCR: RapidOCR → structured result. Caller falls back to GPT Vision.

    Usage:
        ocr = OcrEngine()
        result = ocr.recognize(frame)  # → dict or None
    """

    def __init__(self):
        self._rapid = None
        self._init_rapid()

    def _init_rapid(self):
        try:
            from rapidocr_onnxruntime import RapidOCR
            self._rapid = RapidOCR()
            logger.info("RapidOCR loaded (13MB ONNX model)")
        except Exception as e:
            logger.warning("RapidOCR not available: %s", e)

    def is_available(self) -> bool:
        return self._rapid is not None

    def recognize(self, frame: np.ndarray) -> dict | None:
        """Run RapidOCR + text parser, return structured result or None."""
        text = self._rapid_recognize(frame) if self._rapid else None
        if not text:
            return None

        parsed = _parse_text(text)
        if parsed:
            logger.debug("Local OCR: type=%s options=%d q_len=%d",
                         parsed["type"], len(parsed["options"]), len(parsed["question"]))
        return parsed

    def _rapid_recognize(self, frame: np.ndarray) -> str | None:
        result, _ = self._rapid(frame)
        if not result:
            return None

        entries = []
        for r in result:
            box, text, confidence = r
            if confidence is not None:
                try:
                    c = float(confidence)
                    # Lower threshold for very short text (single-char options like 日/周/月/季)
                    threshold = 0.3 if len(text.strip()) <= 3 else 0.5
                    if c < threshold:
                        continue
                except (ValueError, TypeError):
                    pass
            if not text or not text.strip():
                continue
            y = box[0][1]  # top-left y
            x = box[0][0]  # top-left x
            entries.append((y, x, text.strip()))

        if not entries:
            return None

        entries.sort(key=lambda e: (e[0], e[1]))  # top-to-bottom, left-to-right
        return "\n".join(t for _, _, t in entries)

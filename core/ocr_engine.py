"""Local OCR engine: RapidOCR → layout analysis → text parser → GPT Vision fallback.

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

# Support: A. A． A、 A) A） A (with space)
_OPTION_RE = re.compile(r'^([A-E])[.．、\)）\s]\s*(.+)$', re.UNICODE)

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
    if len(lines) < 2:
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
        # If option lines look packed, try split each line by A-E markers
        expanded = []
        for opt_line in candidate_opts:
            split = _split_options_in_line(opt_line)
            if split:
                expanded.extend(split)
            else:
                expanded.append(opt_line)
        candidate_opts = expanded
        if 2 <= len(candidate_opts) <= 6:
            q_avg_len = sum(len(l) for l in question_lines) / max(len(question_lines), 1)
            opt_lens = [len(l) for l in candidate_opts]
            opt_avg = sum(opt_lens) / len(opt_lens)
            if opt_avg <= q_avg_len * 0.75 or q_avg_len == 0:
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


def _split_options_in_line(text: str) -> list[str] | None:
    """Split a line like 'A.北京 B.上海' into ['A.北京', 'B.上海']."""
    pattern = r'(?:^|\s+)([A-E])[.．、\)）\s]\s*'
    matches = list(re.finditer(pattern, text))
    if len(matches) < 2:
        return None
    parts = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        part = text[start:end].strip()
        # Remove the leading whitespace that the regex matched
        if part:
            parts.append(part)
    return parts if len(parts) >= 2 else None


def _split_packed_line(text: str) -> list[str] | None:
    """Split a single packed line like '题干 A. xxx B. yyy' into multiple lines."""
    # Match A-E option markers preceded by whitespace (not start of string)
    pattern = r'(?:^|\s+)([A-E])[.．、\)）\s]\s*'
    matches = list(re.finditer(pattern, text))
    if len(matches) < 2:
        return None

    first = matches[0]
    if first.start() > 0:
        lines = [text[:first.start()].strip()]
    else:
        lines = []
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        lines.append(text[start:end].strip())

    return lines if len(lines) >= 2 else None


def _parse_text(text: str) -> dict | None:
    """Parse raw OCR text into structured {question, options, type, selected}."""
    text = text.strip()
    if not text:
        return None

    lines = [l.strip() for l in text.split('\n') if l.strip()]

    # ── Force-split single-line packed text (e.g. "题干 A. xxx B. yyy")
    if len(lines) == 1 and len(text) > 20:
        split = _split_packed_line(text)
        if split:
            lines = split

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
    if not option_lines:
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

    # Last resort: if there's any text at all, treat all as question stem
    # (user may have selected a non-question area, but we shouldn't return None)
    if lines:
        result["question"] = _clean_question(" ".join(lines))
        if result["question"]:
            return result

    return None


# ── Layout analysis (uses bounding box coordinates from RapidOCR) ─────


def _group_rows(entries: list[dict], y_tolerance: int = 15) -> list[list[dict]]:
    """Group entries into rows by Y coordinate proximity.

    Entries whose top-edge Y coordinates are within y_tolerance pixels
    of each other are considered the same row.
    """
    if not entries:
        return []

    sorted_entries = sorted(entries, key=lambda e: e["y1"])
    rows: list[list[dict]] = [[sorted_entries[0]]]

    for entry in sorted_entries[1:]:
        prev_y = rows[-1][0]["y1"]
        if abs(entry["y1"] - prev_y) <= y_tolerance:
            rows[-1].append(entry)
        else:
            rows.append([entry])

    for row in rows:
        row.sort(key=lambda e: e["x1"])

    return rows


def _layout_analyze(entries: list[dict]) -> tuple[str, dict] | None:
    """Use bounding box coordinates to separate question stem from options.

    Returns (stem_text, {A: text, B: text, ...}) or None if analysis fails.
    """
    if len(entries) < 2:
        return None

    rows = _group_rows(entries)
    if len(rows) < 2:
        return None

    row_stats = []
    for row in rows:
        min_x = min(e["x1"] for e in row)
        max_x = max(e["x2"] for e in row)
        min_y = min(e["y1"] for e in row)
        max_y = max(e["y2"] for e in row)
        width = max_x - min_x
        height = max(max_y - min_y, 1)
        text = " ".join(e["text"] for e in row).strip()
        row_stats.append({
            "min_x": min_x, "max_x": max_x,
            "width": width, "height": height,
            "text": text, "entries": row,
        })

    # ── Strategy 1: Find option rows by A-E prefix in text ──
    option_row_start = None
    for i, rs in enumerate(row_stats):
        text = rs["text"]
        # Strip radio/checkbox symbols before checking
        text = _OPTION_PREFIX_RE.sub('', text)
        if _OPTION_RE.match(text):
            option_row_start = i
            break

    if option_row_start is not None and option_row_start > 0:
        stem_entries = []
        for rs in row_stats[:option_row_start]:
            stem_entries.extend(rs["entries"])
        option_entries = []
        for rs in row_stats[option_row_start:]:
            option_entries.extend(rs["entries"])

        stem_text = " ".join(e["text"] for e in stem_entries).strip()
        options = _extract_options_from_entries(option_entries, rows[option_row_start:])

        if options and len(options) >= 2:
            return stem_text, options

    # ── Strategy 2: Find option rows by spatial features ──
    # Option rows typically: left-aligned, shorter width, uniform height
    if len(row_stats) >= 3:
        # Find the best split point using width cliff detection
        widths = [rs["width"] for rs in row_stats]
        best_split = None
        best_score = 0

        for i in range(1, min(len(row_stats) - 1, len(row_stats) - 1)):
            before = row_stats[:i]
            after = row_stats[i:]

            if len(after) < 2 or len(after) > 6:
                continue

            # Left alignment consistency in option rows
            after_min_xs = [rs["min_x"] for rs in after]
            x_spread = max(after_min_xs) - min(after_min_xs) if after_min_xs else 0

            # Width: option rows should be narrower than question rows
            before_avg_w = sum(rs["width"] for rs in before) / len(before)
            after_avg_w = sum(rs["width"] for rs in after) / len(after)

            # Height: option rows should be more uniform
            after_heights = [rs["height"] for rs in after]
            h_spread = max(after_heights) - min(after_heights) if len(after_heights) > 1 else 0

            # Text length: option rows should be shorter
            before_avg_len = sum(len(rs["text"]) for rs in before) / len(before)
            after_avg_len = sum(len(rs["text"]) for rs in after) / len(after)

            score = 0
            # Options narrower than question
            if after_avg_w < before_avg_w * 0.85:
                score += 3
            # Options left-aligned
            if x_spread < 30:
                score += 2
            # Options uniform height
            if h_spread < 15:
                score += 1
            # Options shorter text
            if after_avg_len < before_avg_len * 0.7:
                score += 2

            if score > best_score:
                best_score = score
                best_split = i

        if best_split is not None and best_score >= 5:
            stem_entries = []
            for rs in row_stats[:best_split]:
                stem_entries.extend(rs["entries"])
            option_entries = []
            for rs in row_stats[best_split:]:
                option_entries.extend(rs["entries"])

            stem_text = " ".join(e["text"] for e in stem_entries).strip()
            options = _extract_options_from_entries(option_entries, rows[best_split:])

            if options and len(options) >= 2:
                return stem_text, options

    return None


def _extract_options_from_entries(
    option_entries: list[dict], option_rows: list[list[dict]]
) -> dict[str, str]:
    """Extract labeled options from entries, using row grouping when possible."""
    options = {}

    # Try row-by-row extraction first (each row = one option)
    for row in option_rows:
        text = " ".join(e["text"] for e in row).strip()
        text = _OPTION_PREFIX_RE.sub('', text)
        m = _OPTION_RE.match(text)
        if m:
            key, val = m.group(1), m.group(2).strip()
            options[key] = val
        elif text.strip() and options:
            # Continuation of previous option
            last_key = list(options.keys())[-1]
            options[last_key] += " " + text.strip()

    # If row-by-row didn't find A-E labels, try entry-by-entry
    if not options:
        for entry in option_entries:
            text = _OPTION_PREFIX_RE.sub('', entry["text"]).strip()
            m = _OPTION_RE.match(text)
            if m:
                key, val = m.group(1), m.group(2).strip()
                options[key] = val
            elif text and options:
                last_key = list(options.keys())[-1]
                options[last_key] += " " + text

    # Last resort: if still no labeled options, assign A/B/C... by position
    if not options and len(option_rows) >= 2:
        for i, row in enumerate(option_rows):
            if i >= 5:
                break
            text = " ".join(e["text"] for e in row).strip()
            if text:
                options[chr(ord('A') + i)] = text

    return options


# ── OCR Engine ────────────────────────────────────────────────────────


class OcrEngine:
    """Local OCR: RapidOCR → layout analysis → structured result.

    Caller falls back to GPT Vision if this returns None.

    Usage:
        ocr = OcrEngine()
        result = ocr.recognize(frame)  # → dict or None
    """

    def __init__(self):
        self._rapid = None
        # Aggressive preprocessing: upscale small images to 480px (slower, may
        # help tiny fonts). Default off — screen text is usually sharp enough.
        self.aggressive_preprocess = False
        self._init_rapid()

    def _init_rapid(self):
        try:
            from rapidocr_onnxruntime import RapidOCR
            try:
                # Screen captures are always upright → skip the angle
                # classifier model entirely (saves one model pass per frame).
                self._rapid = RapidOCR(use_angle_cls=False)
            except Exception as e:
                logger.warning("RapidOCR(use_angle_cls=False) failed (%s), using defaults", e)
                self._rapid = RapidOCR()
            logger.info("RapidOCR loaded (use_angle_cls=False)")
        except FileNotFoundError as e:
            logger.warning("RapidOCR 初始化失败: 缺少数据文件 (%s)。请重新打包或运行 pip install rapidocr_onnxruntime", e)
        except Exception as e:
            logger.warning("RapidOCR 初始化失败: %s", e)

    def is_available(self) -> bool:
        return self._rapid is not None

    def recognize(self, frame: np.ndarray) -> dict | None:
        """Run RapidOCR + layout analysis + text parser, return structured result or None."""
        if not self._rapid:
            logger.warning("RapidOCR not available")
            return None

        entries = self._rapid_recognize(frame)
        if not entries:
            logger.info("Local OCR: RapidOCR returned no text (image may be blank or low quality)")
            return None

        logger.debug("Local OCR: RapidOCR returned %d text entries", len(entries))

        # Try layout analysis first (uses bounding box coordinates)
        layout_result = _layout_analyze(entries)
        if layout_result:
            stem, options = layout_result
            result = {
                "question": _clean_question(stem),
                "options": options,
                "type": "",
                "selected": [],
            }
            self._infer_type(result)
            logger.debug("Layout analysis: type=%s options=%d q_len=%d",
                         result["type"], len(options), len(result["question"]))
            if result["question"] or result["options"]:
                logger.info("Local OCR: layout analysis succeeded (%d options)", len(options))
                return result
            logger.debug("Layout analysis produced empty result, trying text parser")
        else:
            logger.debug("Layout analysis failed, falling back to text parser")

        # Fallback to text-based parsing
        text = "\n".join(e["text"] for e in entries)
        parsed = _parse_text(text)
        if parsed and (parsed.get("question") or parsed.get("options")):
            logger.info("Local OCR: text parser succeeded (%d options)", len(parsed.get("options", {})))
            logger.debug("Text parser: type=%s options=%d q_len=%d",
                         parsed["type"], len(parsed["options"]), len(parsed["question"]))
            return parsed

        logger.info("Local OCR: text parser failed to extract question/options from %d entries", len(entries))
        logger.debug("Raw OCR text:\n%s", text[:500])
        return None

    @staticmethod
    def _infer_type(result: dict):
        """Infer question type from option count and content."""
        n = len(result["options"])
        if n == 2:
            vals = [result["options"][k].strip().lower() for k in result["options"]]
            judge_words = {"正确", "对", "√", "错误", "错", "×", "right", "wrong", "true", "false"}
            result["type"] = "判断" if any(v in judge_words for v in vals) else "单选"
        elif 3 <= n <= 4:
            result["type"] = "单选"
        elif n > 4:
            result["type"] = "多选"

    def _preprocess_for_ocr(self, frame: np.ndarray) -> np.ndarray:
        """Preprocess screenshot for better OCR accuracy.

        - Scale up small images (minimum 480px on short edge)
        - Convert to grayscale
        - Light sharpen to enhance text edges
        """
        h, w = frame.shape[:2]
        img = frame.copy()

        # Scale up very small images only (upscaling is expensive: OCR cost
        # grows ~quadratically with pixels). Aggressive mode keeps old behavior.
        min_dim = min(h, w)
        should_upscale = min_dim < 240 or (self.aggressive_preprocess and min_dim < 480)
        if should_upscale:
            scale = max(2.0, 480 / min_dim) if min_dim < 240 else 480 / min_dim
            new_w, new_h = int(w * scale), int(h * scale)
            img = cv2.resize(img, (new_w, new_h), interpolation=cv2.INTER_CUBIC)
            logger.debug("OCR preprocess: upscaled %dx%d -> %dx%d", w, h, new_w, new_h)

        # Grayscale
        if len(img.shape) == 3:
            gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        else:
            gray = img

        # Light sharpen to enhance text edges for better detection
        sharpen = np.array([[-0.5, -1, -0.5],
                             [-1,   7,  -1],
                             [-0.5, -1, -0.5]], dtype=np.float32)
        sharpened = cv2.filter2D(gray, -1, sharpen)
        # Blend: 70% sharpened + 30% original to avoid over-sharpening artifacts
        enhanced = cv2.addWeighted(sharpened, 0.7, gray, 0.3, 0)
        return enhanced

    def _rapid_recognize(self, frame: np.ndarray) -> list[dict] | None:
        """Run RapidOCR and return structured entries with bounding box coordinates."""
        processed = self._preprocess_for_ocr(frame)
        result, _ = self._rapid(processed)
        if not result:
            logger.debug("RapidOCR returned empty result after preprocessing")
            return None

        entries = []
        for r in result:
            box, text, confidence = r
            if confidence is not None:
                try:
                    c = float(confidence)
                    # Lower threshold for screen-capture text (fonts may trigger low conf)
                    if c < 0.3:
                        logger.debug("Drop low-conf text '%s' (%.2f)", text, c)
                        continue
                except (ValueError, TypeError):
                    pass
            if not text or not text.strip():
                continue

            # box format: [[x1,y1], [x2,y2], [x3,y3], [x4,y4]]
            # corners: top-left, top-right, bottom-right, bottom-left
            entries.append({
                "text": text.strip(),
                "x1": box[0][0], "y1": box[0][1],  # top-left
                "x2": box[2][0], "y2": box[2][1],  # bottom-right
            })

        if not entries:
            return None

        entries.sort(key=lambda e: (e["y1"], e["x1"]))
        return entries

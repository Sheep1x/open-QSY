import logging
import re
import threading
import time
from typing import Optional

import numpy as np

from core.camera import crop_roi, detect_change
from core.ocr_engine import OcrEngine
from core.quiz_bank import QuizBank
from core.vision_engine import VisionEngine

logger = logging.getLogger(__name__)

# Pattern to detect option markers in question text (when options not separated)
_OPTION_MARKER_RE = re.compile(r'\s+[A-E][.、)）]\s*')


class RecognizeResult:
    def __init__(self):
        self.question: str = ""
        self.options: dict = {}
        self.type: str = ""
        self.selected: list = []
        self.empty: bool = False
        self.matches: list[dict] = []
        self.ai_answer: str = ""
        self.latency: float = 0.0
        self.cached: bool = False


class Matcher:
    def __init__(self, vision: VisionEngine, quiz_bank: QuizBank):
        self.vision = vision
        self.quiz_bank = quiz_bank
        self.ocr_engine = OcrEngine()
        self.match_threshold = 50
        self.enable_ai_answer = True
        self.use_local_ocr = True  # RapidOCR → Windows OCR → GPT Vision
        self._prev_roi: Optional[np.ndarray] = None
        # result cache for repeated questions
        self._cache_lock = threading.Lock()
        self._last_q_hash: int | None = None
        self._cached_matches: list[dict] = []
        self._cached_ai_answer: str = ""
        # OCR frame cache: skip OCR when the frame is pixel-identical
        self._last_frame_hash: int | None = None
        self._last_ocr_result: dict | None = None
        # Settle mode: after a high-confidence match, require a bigger change
        # before re-running recognition (idle question = ~zero cost)
        self.settle_multiplier = 3.0
        self._settled = False

    @staticmethod
    def _question_hash(question: str, options: dict) -> int:
        opts = tuple(sorted(options.items())) if options else ()
        return hash((question, opts))

    def _lookup_cache(self, question: str, options: dict):
        """Return cached (matches, ai_answer) or None on miss."""
        q_hash = self._question_hash(question, options)
        with self._cache_lock:
            if q_hash == self._last_q_hash:
                return self._cached_matches, self._cached_ai_answer
        return None

    def _update_cache(self, question: str, options: dict, matches: list, ai_answer: str):
        q_hash = self._question_hash(question, options)
        with self._cache_lock:
            self._last_q_hash = q_hash
            self._cached_matches = matches
            self._cached_ai_answer = ai_answer

    @staticmethod
    def _frame_hash(frame: np.ndarray) -> int:
        """Exact-content hash of a frame (cheap vs. a full OCR pass)."""
        return hash(frame.tobytes())

    def _recognize(self, frame: np.ndarray) -> dict | None:
        """Tiered OCR: Local OCR → GPT Vision fallback (only if AI is enabled)."""
        # Skip the whole pipeline when the frame is identical to the last one
        frame_hash = self._frame_hash(frame)
        with self._cache_lock:
            if frame_hash == self._last_frame_hash and self._last_ocr_result is not None:
                logger.debug("OCR frame cache hit")
                return self._last_ocr_result

        result = self._recognize_uncached(frame)

        with self._cache_lock:
            self._last_frame_hash = frame_hash
            self._last_ocr_result = result
        return result

    def _recognize_uncached(self, frame: np.ndarray) -> dict | None:
        if self.ocr_engine and self.use_local_ocr:
            if not self.ocr_engine.is_available():
                logger.warning("本地OCR引擎不可用（缺少模型文件或被禁用），跳过本地OCR")
                if not self.enable_ai_answer:
                    return {"empty": True}
                return self.vision.recognize(frame)

            result = self.ocr_engine.recognize(frame)
            if result is not None:
                logger.debug("Local OCR matched: %d options, q_len=%d",
                             len(result.get("options", {})), len(result.get("question", "")))
                return result
            logger.debug("Local OCR returned nothing")
            # Only fall back to GPT Vision if AI answering is enabled
            if not self.enable_ai_answer:
                logger.debug("AI answering disabled, skipping GPT Vision fallback")
                return {"empty": True}
            logger.debug("Falling back to GPT Vision")
        elif not self.enable_ai_answer:
            # Local OCR disabled AND AI disabled → nothing to use
            logger.debug("Local OCR disabled and AI answering disabled")
            return {"empty": True}

        return self.vision.recognize(frame)

    @staticmethod
    def _extract_stem(question: str, options: dict) -> str:
        """Extract question stem when options were not separated.

        If options are already separated, return question as-is.
        Otherwise, try to truncate at the first option marker.
        """
        if options:
            return question

        # Strategy 1: Truncate at first A-E option marker
        m = _OPTION_MARKER_RE.search(question)
        if m and m.start() > 0:
            return question[:m.start()].strip()

        # Strategy 2: Truncate at last question-ending punctuation
        for marker in ('？', '?'):
            idx = question.rfind(marker)
            if 0 < idx < len(question) * 0.7:
                return question[:idx + len(marker)].strip()

        # Cannot truncate reliably — use full text
        return question

    def _match_and_answer(self, result: RecognizeResult):
        """Run quiz search first; AI answer only when the bank didn't match well.

        Quiz search is fast (bigram index, usually <100ms), so calling AI
        optimistically in parallel just wastes API quota for high-confidence
        bank hits.
        """
        if not result.question:
            return

        # check cache first
        cached = self._lookup_cache(result.question, result.options)
        if cached is not None:
            result.matches, result.ai_answer = cached
            result.cached = True
            return

        search_query = self._extract_stem(result.question, result.options)
        result.matches = self.quiz_bank.search(search_query, self.match_threshold)
        if self.enable_ai_answer:
            best_score = result.matches[0]["score"] if result.matches else 0
            if best_score < 95:
                try:
                    ai_result = self.vision.answer(
                        result.question, result.options if result.options else None
                    )
                    if ai_result:
                        result.ai_answer = ai_result
                except Exception:
                    pass

        self._update_cache(result.question, result.options, result.matches, result.ai_answer)

    def process_frame(self, frame: np.ndarray, roi: tuple | None, change_threshold: float = 0.02) -> RecognizeResult | None:
        cropped = crop_roi(frame, roi)

        # Settle mode: after a high-confidence match, only big changes re-trigger
        threshold = change_threshold * (self.settle_multiplier if self._settled else 1.0)
        if not detect_change(self._prev_roi, cropped, threshold):
            return None

        self._prev_roi = cropped.copy()
        result = RecognizeResult()

        t0 = time.time()
        recognized = self._recognize(cropped)

        if recognized is None:
            logger.warning("All OCR tiers returned None")
            result.latency = time.time() - t0
            return None

        if recognized.get("empty"):
            result.empty = True
            result.latency = time.time() - t0
            self._settled = False
            return result

        result.question = recognized.get("question", "")
        result.options = recognized.get("options", {})
        result.type = recognized.get("type", "")
        result.selected = recognized.get("selected", [])

        self._match_and_answer(result)
        result.latency = time.time() - t0

        best = result.matches[0]["score"] if result.matches else 0
        self._settled = best >= 95
        return result

    def process_image(self, image: np.ndarray, roi: tuple | None = None) -> RecognizeResult | None:
        """Recognize a single image (screenshot), no frame diff check."""
        cropped = crop_roi(image, roi)
        result = RecognizeResult()

        t0 = time.time()
        recognized = self._recognize(cropped)

        if recognized is None:
            logger.warning("All OCR tiers returned None")
            result.latency = time.time() - t0
            return None

        if recognized.get("empty"):
            result.empty = True
            result.latency = time.time() - t0
            return result

        result.question = recognized.get("question", "")
        result.options = recognized.get("options", {})
        result.type = recognized.get("type", "")
        result.selected = recognized.get("selected", [])

        self._match_and_answer(result)
        result.latency = time.time() - t0
        return result

    def reset(self):
        self._prev_roi = None
        self._settled = False
        self._last_frame_hash = None
        self._last_ocr_result = None
        with self._cache_lock:
            self._last_q_hash = None

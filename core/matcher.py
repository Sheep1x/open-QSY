import logging
import threading
import time
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
from typing import Optional

import numpy as np

from core.camera import crop_roi, detect_change
from core.ocr_engine import OcrEngine
from core.quiz_bank import QuizBank
from core.vision_engine import VisionEngine

logger = logging.getLogger(__name__)


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
        self.parallel_ai_answer = True
        self._prev_roi: Optional[np.ndarray] = None
        # result cache for repeated questions
        self._cache_lock = threading.Lock()
        self._last_q_hash: int | None = None
        self._cached_matches: list[dict] = []
        self._cached_ai_answer: str = ""

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

    def _recognize(self, frame: np.ndarray) -> dict | None:
        """Tiered OCR: Local OCR → GPT Vision fallback."""
        if self.ocr_engine and self.use_local_ocr:
            result = self.ocr_engine.recognize(frame)
            if result is not None:
                logger.debug("Local OCR matched: %d options, q_len=%d",
                             len(result.get("options", {})), len(result.get("question", "")))
                return result
            logger.debug("Local OCR returned nothing, falling back to GPT Vision")

        return self.vision.recognize(frame)

    def _match_and_answer(self, result: RecognizeResult):
        """Run quiz search + optional AI answer, with parallelism and caching."""
        if not result.question:
            return

        # check cache first
        cached = self._lookup_cache(result.question, result.options)
        if cached is not None:
            result.matches, result.ai_answer = cached
            result.cached = True
            return

        if self.parallel_ai_answer and self.enable_ai_answer:
            # ─── Parallel mode: start quiz search + AI answer simultaneously ───
            # AI answer is started optimistically — if quiz search finds >=95%
            # match the AI result is discarded (wastes one API call but saves latency)
            with ThreadPoolExecutor(max_workers=2) as pool:
                search_fut = pool.submit(
                    self.quiz_bank.search, result.question, self.match_threshold
                )
                ai_fut = pool.submit(
                    self.vision.answer, result.question,
                    result.options if result.options else None
                )
                result.matches = search_fut.result()
                best_score = result.matches[0]["score"] if result.matches else 0
                if best_score < 95:
                    try:
                        result.ai_answer = ai_fut.result() or ""
                    except Exception:
                        pass
                else:
                    # quiz bank matched well — discard AI answer
                    try:
                        ai_fut.result(timeout=0.1)
                    except Exception:
                        pass
        else:
            # ─── Sequential mode: quiz search first, then AI answer if needed ───
            result.matches = self.quiz_bank.search(result.question, self.match_threshold)
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

        if not detect_change(self._prev_roi, cropped, change_threshold):
            return None

        self._prev_roi = cropped.copy()
        result = RecognizeResult()

        t0 = time.time()
        recognized = self._recognize(cropped)
        result.latency = time.time() - t0

        if recognized is None:
            logger.warning("All OCR tiers returned None")
            return None

        if recognized.get("empty"):
            result.empty = True
            return result

        result.question = recognized.get("question", "")
        result.options = recognized.get("options", {})
        result.type = recognized.get("type", "")
        result.selected = recognized.get("selected", [])

        self._match_and_answer(result)
        return result

    def process_image(self, image: np.ndarray, roi: tuple | None = None) -> RecognizeResult | None:
        """Recognize a single image (screenshot), no frame diff check."""
        cropped = crop_roi(image, roi)
        result = RecognizeResult()

        t0 = time.time()
        recognized = self._recognize(cropped)
        result.latency = time.time() - t0

        if recognized is None:
            logger.warning("All OCR tiers returned None")
            return None

        if recognized.get("empty"):
            result.empty = True
            return result

        result.question = recognized.get("question", "")
        result.options = recognized.get("options", {})
        result.type = recognized.get("type", "")
        result.selected = recognized.get("selected", [])

        self._match_and_answer(result)
        return result

    def reset(self):
        self._prev_roi = None
        with self._cache_lock:
            self._last_q_hash = None

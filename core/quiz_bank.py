import json
import logging
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Optional

from rapidfuzz import fuzz

from utils.file_parser import parse_file

logger = logging.getLogger(__name__)

_PARALLEL_THRESHOLD = 1000  # use parallel search when bank exceeds this size
_PARALLEL_CHUNKS = 4

CACHE_DIR = Path.home() / ".open-qsy"
CACHE_FILE = CACHE_DIR / "quiz_bank.json"


class QuizBank:
    def __init__(self):
        self.items: list[dict] = []
        self._questions: list[str] = []
        self._char_sets: list[set[str]] = []
        self._lock = threading.Lock()
        self._source_files: list[str] = []

    def load_file(self, filepath: str, question_col: str = "", answer_col: str = "") -> int:
        items = parse_file(filepath, question_col, answer_col)
        with self._lock:
            self.items.extend(items)
            self._source_files.append(str(filepath))
            self._rebuild_index()
        return len(items)

    def clear(self):
        with self._lock:
            self.items = []
            self._questions = []
            self._char_sets = []
            self._source_files = []

    def search(self, query: str, threshold: float = 50, limit: int = 10) -> list[dict]:
        # Normalize query: collapse newlines / extra spaces
        query = ' '.join(query.split())
        with self._lock:
            n = len(self.items)
            if n == 0 or not query:
                return []
            if n >= _PARALLEL_THRESHOLD:
                return self._parallel_search(query, threshold, limit)
            results = self._search_range(query, threshold, 0, n)
            return [
                {
                    "question": self.items[idx]["question"],
                    "answer": self.items[idx].get("answer", ""),
                    "options": self.items[idx].get("options", {}),
                    "score": score,
                }
                for score, idx in results[:limit]
            ]

    def _search_range(self, query: str, threshold: float, start: int, end: int) -> list[dict]:
        """Search a slice [start, end) of the question bank."""
        query_clean = query.strip()
        query_chars = set(query_clean)
        query_len = len(query_clean)

        results = []
        for i in range(start, end):
            q = self._questions[i]
            q_chars = self._char_sets[i]

            if not query_chars & q_chars:
                continue

            q_len = len(q)
            if q_len > 0 and query_len > 0:
                ratio = min(query_len, q_len) / max(query_len, q_len)
                if ratio * 100 < threshold * 0.5:
                    continue

            score = fuzz.partial_ratio(query_clean, q)
            if score >= threshold:
                results.append((score, i))
                continue

            if score >= threshold * 0.7:
                score = fuzz.token_set_ratio(query_clean, q)
                if score >= threshold:
                    results.append((score, i))

        results.sort(key=lambda x: x[0], reverse=True)
        return results

    def _parallel_search(self, query: str, threshold: float, limit: int) -> list[dict]:
        """Split search across multiple threads for large banks."""
        n = len(self.items)
        chunk_size = max(500, n // _PARALLEL_CHUNKS)
        ranges = [(i, min(i + chunk_size, n)) for i in range(0, n, chunk_size)]

        all_results = []
        with ThreadPoolExecutor(max_workers=min(_PARALLEL_CHUNKS, len(ranges))) as pool:
            futures = [
                pool.submit(self._search_range, query, threshold, s, e)
                for s, e in ranges
            ]
            for fut in futures:
                all_results.extend(fut.result())

        all_results.sort(key=lambda x: x[0], reverse=True)
        all_results = all_results[:limit]

        return [
            {
                "question": self.items[idx]["question"],
                "answer": self.items[idx].get("answer", ""),
                "options": self.items[idx].get("options", {}),
                "score": score,
            }
            for score, idx in all_results
        ]

    def _rebuild_index(self):
        self._questions = [item["question"].strip() for item in self.items]
        self._char_sets = [set(q) for q in self._questions]

    @property
    def count(self) -> int:
        return len(self.items)

    def save_cache(self):
        """Persist quiz bank items to disk cache."""
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        with self._lock:
            data = {
                "source_files": self._source_files,
                "items": self.items,
            }
        with open(CACHE_FILE, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        logger.info("Quiz bank cached: %d items", len(self.items))

    def load_cache(self) -> bool:
        """Load quiz bank from disk cache. Returns True if loaded."""
        if not CACHE_FILE.exists():
            return False
        try:
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            with self._lock:
                self.items = data.get("items", [])
                self._source_files = data.get("source_files", [])
                self._rebuild_index()
            logger.info("Quiz bank loaded from cache: %d items", len(self.items))
            return len(self.items) > 0
        except Exception as e:
            logger.warning("Failed to load quiz bank cache: %s", e)
            return False

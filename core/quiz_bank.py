import json
import logging
import threading
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from rapidfuzz import fuzz

from core.config import get_config_dir
from utils.file_parser import parse_file

logger = logging.getLogger(__name__)

_PARALLEL_THRESHOLD = 1000
_PARALLEL_CHUNKS = 4

# Length buckets for quick filtering
_LEN_BUCKETS = [(0, 20), (20, 50), (50, 100), (100, 200), (200, 500), (500, 99999)]
_LRU_SIZE = 20


def _bigrams(text: str) -> set[str]:
    """Return set of character bigrams from text."""
    return {text[i:i+2] for i in range(len(text) - 1)} if len(text) >= 2 else {text}


def _bucket_for(length: int) -> int:
    """Return bucket index for a given string length."""
    for i, (lo, hi) in enumerate(_LEN_BUCKETS):
        if lo <= length < hi:
            return i
    return len(_LEN_BUCKETS) - 1


class QuizBank:
    def __init__(self):
        self.items: list[dict] = []
        self._questions: list[str] = []
        self._questions_full: list[str] = []
        self._char_sets: list[set[str]] = []
        self._char_sets_full: list[set[str]] = []
        self._bigram_index: dict[str, set[int]] = {}  # bigram → set of item indices
        self._bigram_index_full: dict[str, set[int]] = {}
        self._len_buckets: list[list[int]] = [[] for _ in _LEN_BUCKETS]  # bucket → list of indices
        self._len_buckets_full: list[list[int]] = [[] for _ in _LEN_BUCKETS]
        self._lock = threading.Lock()
        self._source_files: list[str] = []
        # LRU search cache
        self._search_cache: OrderedDict[str, list[dict]] = OrderedDict()

    def load_file(self, filepath: str, question_col: str = "", answer_col: str = "") -> int:
        items = parse_file(filepath, question_col, answer_col)
        with self._lock:
            self.items.extend(items)
            self._source_files.append(str(filepath))
            self._rebuild_index()
            self._search_cache.clear()
        return len(items)

    def clear(self):
        with self._lock:
            self.items = []
            self._questions = []
            self._questions_full = []
            self._char_sets = []
            self._char_sets_full = []
            self._bigram_index = {}
            self._bigram_index_full = {}
            self._len_buckets = [[] for _ in _LEN_BUCKETS]
            self._len_buckets_full = [[] for _ in _LEN_BUCKETS]
            self._source_files = []
            self._search_cache.clear()

    def search(self, query: str, threshold: float = 50, limit: int = 10) -> list[dict]:
        query = ' '.join(query.split())

        # LRU cache lookup
        cache_key = f"{query}|{threshold}|{limit}"
        with self._lock:
            if cache_key in self._search_cache:
                self._search_cache.move_to_end(cache_key)
                return self._search_cache[cache_key]

        with self._lock:
            n = len(self.items)
            if n == 0 or not query:
                return []
            if n >= _PARALLEL_THRESHOLD:
                results = self._parallel_search(query, threshold, limit)
            else:
                raw = self._search_range(query, threshold, 0, n)
                results = [
                    {
                        "question": self.items[idx]["question"],
                        "answer": self.items[idx].get("answer", ""),
                        "options": self.items[idx].get("options", {}),
                        "score": score,
                    }
                    for score, idx in raw[:limit]
                ]

        # Store in LRU cache
        with self._lock:
            self._search_cache[cache_key] = results
            if len(self._search_cache) > _LRU_SIZE:
                self._search_cache.popitem(last=False)

        return results

    def _get_candidate_indices(self, query: str, query_len: int, threshold: float) -> set[int]:
        """Use bigram index + length buckets to get candidate item indices."""
        query_bgs = _bigrams(query)
        q_bucket = _bucket_for(query_len)

        # Candidates from bigram overlap
        hit_counts: dict[int, int] = {}
        for bg in query_bgs:
            for idx in self._bigram_index.get(bg, set()):
                hit_counts[idx] = hit_counts.get(idx, 0) + 1

        # Also check full-text bigram index
        for bg in query_bgs:
            for idx in self._bigram_index_full.get(bg, set()):
                hit_counts[idx] = hit_counts.get(idx, 0) + 1

        if not hit_counts:
            return set()

        # Filter by minimum bigram overlap (at least 1 bigram shared per 10 chars of query)
        min_overlap = max(1, len(query_bgs) * 0.15)
        candidates = {idx for idx, cnt in hit_counts.items() if cnt >= min_overlap}

        # Length bucket filter: only check buckets within reasonable length range
        min_len = query_len * (threshold * 0.5 / 100)
        max_len = query_len / (threshold * 0.5 / 100) if threshold * 0.5 > 0 else 99999
        min_bucket = _bucket_for(int(min_len))
        max_bucket = _bucket_for(int(max_len) + 1)

        # Union of indices in relevant length buckets
        bucket_candidates = set()
        for b in range(min_bucket, max_bucket + 1):
            if b < len(self._len_buckets):
                bucket_candidates.update(self._len_buckets[b])
            if b < len(self._len_buckets_full):
                bucket_candidates.update(self._len_buckets_full[b])

        # Intersection of bigram candidates and length candidates (if length set is non-empty)
        if bucket_candidates:
            candidates = candidates & bucket_candidates

        return candidates

    def _score_item(self, query: str, query_len: int, i: int, threshold: float) -> float:
        """Score a single item against query, returns best score from both columns."""
        q = self._questions[i]
        q_chars = self._char_sets[i]

        # ── Exact substring match (fast path) ──
        if query_len >= 4:
            if query in q:
                return 100.0
            if q in query:
                return 95.0

        # ── Score question column ──
        score = 0
        if query_chars := (set(query) & q_chars):
            q_len = len(q)
            if q_len > 0:
                ratio = min(query_len, q_len) / max(query_len, q_len)
                if ratio * 100 >= threshold * 0.5:
                    score = fuzz.partial_ratio(query, q)
                    if score < threshold * 0.7:
                        score = max(score, fuzz.token_set_ratio(query, q))

        # ── Score full-text column (fallback) ──
        if score < threshold:
            q_full = self._questions_full[i]
            if q_full != q:
                # Exact match on full-text
                if query_len >= 4 and query in q_full:
                    return 100.0

                q_full_chars = self._char_sets_full[i]
                if set(query) & q_full_chars:
                    qf_len = len(q_full)
                    if qf_len > 0:
                        ratio = min(query_len, qf_len) / max(query_len, qf_len)
                        if ratio * 100 >= threshold * 0.5:
                            score_full = fuzz.partial_ratio(query, q_full)
                            if score_full < threshold * 0.7:
                                score_full = max(score_full, fuzz.token_set_ratio(query, q_full))
                            score = max(score, score_full)

        return score

    def _search_range(self, query: str, threshold: float, start: int, end: int) -> list[tuple[float, int]]:
        """Search items [start, end) with all optimizations."""
        query_clean = query.strip()
        query_len = len(query_clean)

        # Use candidate filtering for the full range
        candidates = self._get_candidate_indices(query_clean, query_len, threshold)
        # Intersect with [start, end) range
        candidates = {idx for idx in candidates if start <= idx < end}

        # Fallback: if candidate filtering yields nothing, scan all (safety net)
        if not candidates:
            candidates = set(range(start, end))

        results = []
        for i in candidates:
            score = self._score_item(query_clean, query_len, i, threshold)

            if score >= threshold:
                results.append((score, i))

            # Perfect score early termination
            if score >= 100.0:
                break

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
        self._questions_full = [
            item.get("question_full", item["question"]).strip()
            for item in self.items
        ]
        self._char_sets = [set(q) for q in self._questions]
        self._char_sets_full = [set(q) for q in self._questions_full]

        # Build bigram inverted index
        self._bigram_index = {}
        for i, q in enumerate(self._questions):
            for bg in _bigrams(q):
                if bg not in self._bigram_index:
                    self._bigram_index[bg] = set()
                self._bigram_index[bg].add(i)

        self._bigram_index_full = {}
        for i, qf in enumerate(self._questions_full):
            if qf != self._questions[i]:
                for bg in _bigrams(qf):
                    if bg not in self._bigram_index_full:
                        self._bigram_index_full[bg] = set()
                    self._bigram_index_full[bg].add(i)

        # Build length buckets
        self._len_buckets = [[] for _ in _LEN_BUCKETS]
        self._len_buckets_full = [[] for _ in _LEN_BUCKETS]
        for i, q in enumerate(self._questions):
            b = _bucket_for(len(q))
            self._len_buckets[b].append(i)
        for i, qf in enumerate(self._questions_full):
            if qf != self._questions[i]:
                b = _bucket_for(len(qf))
                self._len_buckets_full[b].append(i)

    @property
    def count(self) -> int:
        return len(self.items)

    def save_cache(self):
        """Persist quiz bank items to disk cache."""
        cache_dir = get_config_dir()
        cache_dir.mkdir(parents=True, exist_ok=True)
        cache_file = cache_dir / "quiz_bank.json"
        with self._lock:
            data = {
                "source_files": self._source_files,
                "items": self.items,
            }
        with open(cache_file, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        logger.info("Quiz bank cached: %d items", len(self.items))

    def load_cache(self) -> bool:
        """Load quiz bank from disk cache. Returns True if loaded."""
        cache_file = get_config_dir() / "quiz_bank.json"
        if not cache_file.exists():
            return False
        try:
            with open(cache_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            with self._lock:
                self.items = data.get("items", [])
                self._source_files = data.get("source_files", [])
                self._rebuild_index()
                self._search_cache.clear()
            logger.info("Quiz bank loaded from cache: %d items", len(self.items))
            return len(self.items) > 0
        except Exception as e:
            logger.warning("Failed to load quiz bank cache: %s", e)
            return False

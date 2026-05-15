import json
from pathlib import Path

CONFIG_DIR = Path.home() / ".open-qsy"
CONFIG_FILE = CONFIG_DIR / "config.json"


def _ensure_dir():
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)


def load_config() -> dict:
    _ensure_dir()
    if CONFIG_FILE.exists():
        with open(CONFIG_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return get_default_config()


def save_config(cfg: dict):
    _ensure_dir()
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def get_default_config() -> dict:
    return {
        "base_url": "https://api.openai.com/v1",
        "api_key": "",
        "model": "gpt-4o",
        "camera_index": 0,
        "roi": None,
        "frame_interval": 1.0,
        "match_threshold": 70,
        "change_threshold": 0.02,
        "enable_ai_answer": True,
        "parallel_ai_answer": False,
        "use_local_ocr": True,
    }

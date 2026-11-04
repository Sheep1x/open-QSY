import json
import sys
from pathlib import Path

PORTABLE_MARKER = "portable.marker"

# Default global hotkeys (values must use keys from hotkey_manager.VK_MAP)
DEFAULT_HOTKEYS = {
    "select_area": "ctrl+alt+a",
    "capture_once": "ctrl+alt+s",
    "toggle_capture": "ctrl+alt+d",
    "passthrough": "ctrl+alt+p",
    "toggle_ui": "ctrl+alt+h",
}


def get_app_dir() -> Path:
    """Directory of the running exe (frozen) or the project root (source run)."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


def is_portable() -> bool:
    """Portable mode: a portable.marker file sits next to the exe/project."""
    return (get_app_dir() / PORTABLE_MARKER).exists()


def get_config_dir() -> Path:
    """Config/cache location: app dir in portable mode, user home otherwise."""
    if is_portable():
        return get_app_dir() / "data"
    return Path.home() / ".open-qsy"


def _ensure_dir():
    get_config_dir().mkdir(parents=True, exist_ok=True)


def load_config() -> dict:
    _ensure_dir()
    config_file = get_config_dir() / "config.json"
    if config_file.exists():
        with open(config_file, "r", encoding="utf-8") as f:
            return json.load(f)
    return get_default_config()


def save_config(cfg: dict):
    _ensure_dir()
    with open(get_config_dir() / "config.json", "w", encoding="utf-8") as f:
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
        "use_local_ocr": True,
        "ocr_preprocess": False,
        "overlay_mode": "none",
        "overlay_bg_color": "transparent",
        "overlay_bg_alpha": 220,
        "selector_border_color": "#00ff88",
        "overlay_border_color": "#00ff88",
        "overlay_text_color": "#e0e0e0",
    }

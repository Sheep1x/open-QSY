import sys
from pathlib import Path


def get_resource_path(rel_path: str) -> str:
    """Resolve resource path for both dev and PyInstaller bundled modes."""
    if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
        # PyInstaller onedir: _MEIPASS points to _internal/ where resources live
        base = Path(sys._MEIPASS)
    else:
        base = Path(__file__).parent.parent
    return str(base / rel_path)


def icon_path() -> str:
    return get_resource_path("resources/icon.ico")

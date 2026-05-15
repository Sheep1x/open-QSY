import ctypes
import logging

from PyQt6.QtCore import QObject, QTimer, Qt

logger = logging.getLogger(__name__)

user32 = ctypes.windll.user32

MOD_ALT = 0x0001
MOD_CONTROL = 0x0002
MOD_SHIFT = 0x0004

VK_MAP = {
    "a": 0x41, "b": 0x42, "c": 0x43, "d": 0x44, "e": 0x45,
    "f": 0x46, "g": 0x47, "h": 0x48, "i": 0x49, "j": 0x4A,
    "k": 0x4B, "l": 0x4C, "m": 0x4D, "n": 0x4E, "o": 0x4F,
    "p": 0x50, "q": 0x51, "r": 0x52, "s": 0x53, "t": 0x54,
    "u": 0x55, "v": 0x56, "w": 0x57, "x": 0x58, "y": 0x59,
    "z": 0x5A,
    "f1": 0x70, "f2": 0x71, "f3": 0x72, "f4": 0x73,
    "f5": 0x74, "f6": 0x75, "f7": 0x76, "f8": 0x77,
    "f9": 0x78, "f10": 0x79, "f11": 0x7A, "f12": 0x7B,
    "space": 0x20, "tab": 0x09, "enter": 0x0D,
    "esc": 0x1B,
}


def _key_down(vk: int) -> bool:
    return bool(user32.GetAsyncKeyState(vk) & 0x8000)


VK_CONTROL = 0x11
VK_MENU = 0x12
VK_SHIFT = 0x10

MOD_VK = {"ctrl": VK_CONTROL, "alt": VK_MENU, "shift": VK_SHIFT}


class HotkeyManager(QObject):
    """Polling-based global hotkey detector using QTimer."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._hotkeys: list[tuple[str, list[int], int, callable]] = []
        self._pressed: set[str] = set()  # track which combos already fired
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._poll)

    def register(self, combo: str, callback: callable):
        parts = combo.lower().split("+")
        mod_vks = []
        main_vk = 0
        for p in parts:
            p = p.strip()
            if p in MOD_VK:
                mod_vks.append(MOD_VK[p])
            elif p in VK_MAP:
                main_vk = VK_MAP[p]
        self._hotkeys.append((combo, mod_vks, main_vk, callback))
        logger.info("Registered hotkey: %s", combo)

    def start(self):
        self._timer.start(80)  # poll every 80ms

    def stop(self):
        self._timer.stop()

    def _poll(self):
        for combo, mod_vks, main_vk, callback in self._hotkeys:
            all_pressed = all(_key_down(vk) for vk in mod_vks) and _key_down(main_vk)
            if all_pressed:
                if combo not in self._pressed:
                    self._pressed.add(combo)
                    try:
                        callback()
                    except Exception as e:
                        logger.error("Hotkey callback error: %s", e)
            else:
                self._pressed.discard(combo)

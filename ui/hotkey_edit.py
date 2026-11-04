"""Editable global-hotkey capture widget.

Click the button, then press a new key combination. The combo format matches
core.hotkey_manager ("ctrl+alt+s" style), restricted to keys in its VK_MAP.
"""
from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QKeySequence
from PyQt6.QtWidgets import QPushButton

# Keys supported by core.hotkey_manager.VK_MAP
_SUPPORTED = {chr(ord("a") + i) for i in range(26)} \
    | {f"f{i}" for i in range(1, 13)} \
    | {"space", "tab", "enter", "esc"}

_MOD_KEYS = {
    Qt.Key.Key_Control, Qt.Key.Key_Alt, Qt.Key.Key_Shift, Qt.Key.Key_Meta,
}


def display_combo(combo: str) -> str:
    """'ctrl+alt+s' -> 'Ctrl+Alt+S'."""
    return "+".join(p.capitalize() for p in combo.split("+"))


class HotkeyEdit(QPushButton):
    """Button showing the current combo; click then press a new one."""
    capture_started = pyqtSignal()
    capture_finished = pyqtSignal()
    combo_changed = pyqtSignal(str)  # new combo, e.g. "ctrl+alt+q"

    def __init__(self, combo: str, parent=None):
        super().__init__(parent)
        self._combo = combo
        self._capturing = False
        self.setFixedSize(180, 30)
        self._refresh_text()
        self.clicked.connect(self._begin)

    def combo(self) -> str:
        return self._combo

    def set_combo(self, combo: str):
        self._combo = combo
        self._capturing = False
        self._refresh_text()

    def _refresh_text(self):
        if self._capturing:
            self.setText("按下新快捷键…")
        else:
            self.setText(display_combo(self._combo))

    def _begin(self):
        if self._capturing:
            return
        self._capturing = True
        self.setFocus()
        self._refresh_text()
        self.capture_started.emit()

    def _cancel(self):
        self._capturing = False
        self._refresh_text()
        self.capture_finished.emit()

    def keyPressEvent(self, event):
        if not self._capturing:
            return super().keyPressEvent(event)
        key = event.key()
        if key in _MOD_KEYS:
            return  # ignore pure modifier presses
        if key == Qt.Key.Key_Escape:
            self._cancel()
            return

        mods = event.modifiers()
        parts = []
        if mods & Qt.KeyboardModifier.ControlModifier:
            parts.append("ctrl")
        if mods & Qt.KeyboardModifier.AltModifier:
            parts.append("alt")
        if mods & Qt.KeyboardModifier.ShiftModifier:
            parts.append("shift")
        name = QKeySequence(int(key)).toString().lower()
        if not parts or not name or name not in _SUPPORTED:
            self.setText("需包含 Ctrl/Alt（Esc 取消）")
            return

        self._combo = "+".join(parts + [name])
        self._capturing = False
        self._refresh_text()
        self.capture_finished.emit()
        self.combo_changed.emit(self._combo)

    def focusOutEvent(self, event):
        super().focusOutEvent(event)
        if self._capturing:
            self._cancel()

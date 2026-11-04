"""Overlay widgets for displaying recognition results on top of other windows.

Two modes:
- FloatingPanel: resizable card showing answer, question preview, source
- EdgeBar: resizable strip at screen edge, expandable for more detail

Both support:
- Drag by title bar / handle
- Resize by grip
- Semi-transparent background with user-configurable color
- Passthrough mode toggled by Ctrl+Alt+P hotkey
"""
import logging

from PyQt6.QtCore import Qt, QPoint, QRect
from PyQt6.QtGui import QFont, QColor, QCursor, QPainter, QPen, QPixmap
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
    QTextEdit, QSizeGrip, QApplication,
)

logger = logging.getLogger(__name__)

_OVERLAY_BORDER = "#00ff88"
_TEXT_COLOR = "#e0e0e0"
_BAR_MIN_HEIGHT = 40
_BAR_MAX_HEIGHT = 400


def is_transparent_color(color: str) -> bool:
    """True if the given color string means 'no border'."""
    return (color or "").strip().lower() in ("transparent", "none", "透明")


def _fmt_answer(answer: str, options: dict) -> str:
    """Return option letter(s) only, without content."""
    if not answer:
        return ""
    if all(c.upper() in options for c in answer):
        return answer.upper()
    return answer


class FloatingPanel(QWidget):
    """Always-on-top resizable panel showing the best match result."""

    def __init__(self, bg_color: str = "#0f0f23", bg_alpha: int = 220,
                 border_color: str = _OVERLAY_BORDER, text_color: str = _TEXT_COLOR, parent=None):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setMinimumSize(220, 80)
        self.resize(320, 160)

        self._bg_color = bg_color
        self._bg_alpha = bg_alpha
        self._border_color = border_color
        self._text_color = text_color
        self._passthrough = False
        self._drag_pos: QPoint | None = None

        self._build_ui()
        self._apply_bg()
        self._apply_text_colors()
        self._position_top_right()

    def _bg_rgba(self) -> str:
        if is_transparent_color(self._bg_color):
            return "transparent"
        c = QColor(self._bg_color)
        return f"rgba({c.red()}, {c.green()}, {c.blue()}, {self._bg_alpha})"

    def _accent(self) -> str:
        """Border color, or a visible fallback when transparent."""
        return "#888888" if is_transparent_color(self._border_color) else self._border_color

    def _border_css(self) -> str:
        if is_transparent_color(self._border_color):
            return "border: none;"
        return f"border: 1px solid {self._border_color};"

    def _apply_bg(self):
        rgba = self._bg_rgba()
        self._container.setStyleSheet(f"""
            QWidget#overlayContainer {{
                background-color: {rgba};
                {self._border_css()}
                border-radius: 8px;
            }}
        """)

    def _apply_text_colors(self):
        accent = self._accent()
        self._title_label.setStyleSheet(
            f"color: {accent}; font-size: 13px; font-weight: bold; border: none;")
        self._answer_label.setStyleSheet(
            f"color: {accent}; font-size: 18px; font-weight: bold; border: none; padding: 2px 0;")
        self._question_label.setStyleSheet(
            f"color: {self._text_color}; font-size: 12px; border: none;")
        self._btn_passthrough.setStyleSheet(self._passthrough_btn_style(self._passthrough))

    def set_bg_color(self, color: str, alpha: int = 220):
        self._bg_color = color
        self._bg_alpha = alpha
        self._apply_bg()

    def set_border_color(self, color: str):
        self._border_color = color
        self._apply_bg()
        self._apply_text_colors()

    def set_text_color(self, color: str):
        self._text_color = color
        self._apply_text_colors()

    def _passthrough_btn_style(self, active: bool) -> str:
        if active:
            return """
                QPushButton {
                    background-color: #ff4444; color: white; font-size: 11px;
                    border: 1px solid #ff4444; border-radius: 3px;
                }
            """
        accent = self._accent()
        return f"""
            QPushButton {{
                background-color: transparent; color: #aaa; font-size: 11px;
                border: 1px solid #555; border-radius: 3px;
            }}
            QPushButton:hover {{ border-color: {accent}; color: {accent}; }}
        """

    def _build_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self._container = QWidget()
        self._container.setObjectName("overlayContainer")
        self._container.setCursor(QCursor(Qt.CursorShape.SizeAllCursor))
        container_layout = QVBoxLayout(self._container)
        container_layout.setContentsMargins(12, 8, 12, 10)
        container_layout.setSpacing(6)

        # ── Title bar ──
        title_row = QHBoxLayout()
        title_row.setSpacing(4)
        self._title_label = QLabel("◇ 题眼")
        title_row.addWidget(self._title_label)
        title_row.addStretch()

        self._btn_passthrough = QPushButton("穿透")
        self._btn_passthrough.setFixedSize(48, 22)
        self._btn_passthrough.clicked.connect(self._toggle_passthrough)
        title_row.addWidget(self._btn_passthrough)

        self._btn_close = QPushButton("×")
        self._btn_close.setFixedSize(22, 22)
        self._btn_close.setStyleSheet("""
            QPushButton {
                background-color: transparent; color: #aaa; font-size: 14px;
                border: none; border-radius: 3px;
            }
            QPushButton:hover { background-color: #ff4444; color: white; }
        """)
        self._btn_close.clicked.connect(self.hide)
        title_row.addWidget(self._btn_close)

        container_layout.addLayout(title_row)

        # ── Answer (big text, word wrap) ──
        self._answer_label = QLabel("等待识别...")
        self._answer_label.setWordWrap(True)
        self._answer_label.setSizePolicy(
            self._answer_label.sizePolicy().horizontalPolicy(),
            self._answer_label.sizePolicy().verticalPolicy(),
        )
        container_layout.addWidget(self._answer_label)

        # ── Question preview (word wrap, no truncation) ──
        self._question_label = QLabel("")
        self._question_label.setWordWrap(True)
        container_layout.addWidget(self._question_label)

        # ── Source tag ──
        self._source_label = QLabel("")
        self._source_label.setStyleSheet("color: #888; font-size: 11px; border: none;")
        container_layout.addWidget(self._source_label)

        # ── Resize grip ──
        grip_row = QHBoxLayout()
        grip_row.addStretch()
        self._grip = QSizeGrip(self._container)
        self._grip.setFixedSize(14, 14)
        self._grip.setStyleSheet("""
            QSizeGrip { background-color: transparent; border: none; }
        """)
        grip_row.addWidget(self._grip)
        container_layout.addLayout(grip_row)

        layout.addWidget(self._container)

    def _position_top_right(self):
        screen = QApplication.primaryScreen().geometry()
        x = screen.right() - self.width() - 20
        y = screen.top() + 20
        self.move(x, y)

    def _toggle_passthrough(self):
        self._passthrough = not self._passthrough
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, self._passthrough)
        if self._passthrough:
            self._btn_passthrough.setText("可点击")
        else:
            self._btn_passthrough.setText("穿透")
        self._btn_passthrough.setStyleSheet(self._passthrough_btn_style(self._passthrough))

    def set_passthrough(self, enabled: bool):
        if enabled != self._passthrough:
            self._toggle_passthrough()

    def update_result(self, result):
        if result is None or result.empty:
            self._answer_label.setText("未检测到题目")
            self._answer_label.setStyleSheet(f"color: #ffaa00; font-size: 18px; font-weight: bold; border: none;")
            self._question_label.setText("")
            self._source_label.setText("")
            return

        if result.matches:
            best = result.matches[0]
            opts = best.get("options", {})
            ans = _fmt_answer(best["answer"], opts)
            score = best["score"]
            if ans:
                self._answer_label.setText(f"✅ {ans}  ({score:.0f}%)")
            else:
                self._answer_label.setText(f"⚠️ 无答案  ({score:.0f}%)")
            color = "#00ff88" if score >= 95 else ("#ffaa00" if score >= 80 else "#ff6666")
            self._answer_label.setStyleSheet(f"color: {color}; font-size: 18px; font-weight: bold; border: none;")
            self._source_label.setText("来源: 题库")
        else:
            self._answer_label.setText("⚠️ 题库未匹配")
            self._answer_label.setStyleSheet(f"color: #ff6666; font-size: 18px; font-weight: bold; border: none;")
            self._source_label.setText("")

        if result.ai_answer:
            if not result.matches or (result.matches[0]["score"] < 95):
                self._source_label.setText("来源: AI辅助")
                self._answer_label.setText(f"🤖 {result.ai_answer}")
                self._answer_label.setStyleSheet(f"color: #00aaff; font-size: 18px; font-weight: bold; border: none;")

        self._question_label.setText(result.question or "")

    # ── Drag by title bar only ──
    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and not self._passthrough:
            self._drag_pos = event.globalPosition().toPoint() - self.pos()

    def mouseMoveEvent(self, event):
        if self._drag_pos is not None:
            self.move(event.globalPosition().toPoint() - self._drag_pos)

    def mouseReleaseEvent(self, event):
        self._drag_pos = None


class EdgeBar(QWidget):
    """Resizable always-on-top strip, expandable with drag-resize."""

    def __init__(self, bg_color: str = "#0f0f23", bg_alpha: int = 220,
                 border_color: str = _OVERLAY_BORDER, text_color: str = _TEXT_COLOR, parent=None):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)
        self.setMinimumSize(200, _BAR_MIN_HEIGHT)
        self.setMaximumHeight(_BAR_MAX_HEIGHT)

        self._bg_color = bg_color
        self._bg_alpha = bg_alpha
        self._border_color = border_color
        self._text_color = text_color
        self._drag_pos: QPoint | None = None

        self._build_ui()
        self._apply_bg()
        self._apply_text_colors()
        self._position_top()

    def _bg_rgba(self) -> str:
        if is_transparent_color(self._bg_color):
            return "transparent"
        c = QColor(self._bg_color)
        return f"rgba({c.red()}, {c.green()}, {c.blue()}, {self._bg_alpha})"

    def _accent(self) -> str:
        """Border color, or a visible fallback when transparent."""
        return "#888888" if is_transparent_color(self._border_color) else self._border_color

    def _border_css(self) -> str:
        if is_transparent_color(self._border_color):
            return "border: none;"
        return f"border: 1px solid {self._border_color};"

    def _apply_bg(self):
        rgba = self._bg_rgba()
        self._container.setStyleSheet(f"""
            QWidget#edgeBarContainer {{
                background-color: {rgba};
                {self._border_css()}
                border-radius: 6px;
            }}
        """)
        if is_transparent_color(self._bg_color):
            detail_bg = "transparent"
        else:
            c = QColor(self._bg_color)
            detail_bg = f"rgba({c.red()}, {c.green()}, {c.blue()}, {min(self._bg_alpha + 30, 255)})"
        self._detail.setStyleSheet(f"""
            QTextEdit {{
                background-color: {detail_bg};
                color: {self._text_color}; border: none; border-radius: 4px;
                font-size: 12px; padding: 4px;
            }}
        """)

    def _apply_text_colors(self):
        self._answer_label.setStyleSheet(
            f"color: {self._accent()}; font-size: 15px; font-weight: bold; border: none;")
        self._question_preview.setStyleSheet(
            f"color: {self._text_color}; font-size: 11px; border: none;")

    def set_bg_color(self, color: str, alpha: int = 220):
        self._bg_color = color
        self._bg_alpha = alpha
        self._apply_bg()

    def set_border_color(self, color: str):
        self._border_color = color
        self._apply_bg()

    def set_text_color(self, color: str):
        self._text_color = color
        self._apply_bg()
        self._apply_text_colors()

    def _build_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        self._container = QWidget()
        self._container.setObjectName("edgeBarContainer")
        self._container.setCursor(QCursor(Qt.CursorShape.SizeAllCursor))
        container_layout = QVBoxLayout(self._container)
        container_layout.setContentsMargins(10, 6, 10, 6)
        container_layout.setSpacing(4)

        # ── Main row ──
        main_row = QHBoxLayout()
        main_row.setSpacing(8)

        self._handle = QLabel("⣿")
        self._handle.setStyleSheet("color: #555; font-size: 14px; border: none;")
        self._handle.setCursor(QCursor(Qt.CursorShape.OpenHandCursor))
        main_row.addWidget(self._handle)

        self._answer_label = QLabel("等待识别...")
        self._answer_label.setWordWrap(True)
        main_row.addWidget(self._answer_label, stretch=1)

        self._source_tag = QLabel("")
        self._source_tag.setStyleSheet("color: #00aaff; font-size: 11px; border: none;")
        main_row.addWidget(self._source_tag)

        self._btn_close = QPushButton("×")
        self._btn_close.setFixedSize(18, 18)
        self._btn_close.setStyleSheet("""
            QPushButton {
                background-color: transparent; color: #888; font-size: 12px;
                border: none;
            }
            QPushButton:hover { color: #ff4444; }
        """)
        self._btn_close.clicked.connect(self.hide)
        main_row.addWidget(self._btn_close)

        container_layout.addLayout(main_row)

        # ── Question preview row ──
        self._question_preview = QLabel("")
        self._question_preview.setWordWrap(True)
        container_layout.addWidget(self._question_preview)

        # ── Detail area (visible when expanded) ──
        self._detail = QTextEdit()
        self._detail.setReadOnly(True)
        self._detail.setMaximumHeight(0)
        self._detail.hide()
        container_layout.addWidget(self._detail)

        # ── Resize grip ──
        grip_row = QHBoxLayout()
        grip_row.addStretch()
        self._grip = QSizeGrip(self._container)
        self._grip.setFixedSize(14, 14)
        self._grip.setStyleSheet("QSizeGrip { background-color: transparent; border: none; }")
        grip_row.addWidget(self._grip)
        container_layout.addLayout(grip_row)

        layout.addWidget(self._container)

    def _position_top(self):
        screen = QApplication.primaryScreen().geometry()
        self.resize(screen.width() - 40, _BAR_MIN_HEIGHT)
        self.move(20, screen.top() + 10)

    def update_result(self, result):
        if result is None or result.empty:
            self._answer_label.setText("未检测到题目")
            self._answer_label.setStyleSheet(f"color: #ffaa00; font-size: 15px; font-weight: bold; border: none;")
            self._question_preview.setText("")
            self._source_tag.setText("")
            self._detail.hide()
            self._detail.setMaximumHeight(0)
            return

        if result.matches:
            best = result.matches[0]
            opts = best.get("options", {})
            ans = _fmt_answer(best["answer"], opts)
            score = best["score"]
            if ans:
                self._answer_label.setText(f"✅ {ans}  {score:.0f}%")
            else:
                self._answer_label.setText(f"⚠️ 无答案  {score:.0f}%")
            color = "#00ff88" if score >= 95 else ("#ffaa00" if score >= 80 else "#ff6666")
            self._answer_label.setStyleSheet(f"color: {color}; font-size: 15px; font-weight: bold; border: none;")
            self._source_tag.setText("[题库]")

            lines = []
            for m in result.matches[:5]:
                s = m["score"]
                m_opts = m.get("options", {})
                ans_raw = m.get("answer", "")
                a_display = ""
                if ans_raw and all(c.upper() in m_opts for c in ans_raw):
                    parts = [f"{c}. {m_opts[c.upper()]}" for c in ans_raw]
                    a_display = "  ".join(parts)
                elif ans_raw:
                    a_display = ans_raw
                c = "#00ff88" if s >= 95 else ("#ffaa00" if s >= 80 else "#ff6666")
                lines.append(f'<span style="color:{c}">[{s:.0f}%]</span> {m["question"][:80]} → {a_display}')
            if lines:
                self._detail.setHtml("<br>".join(lines))
                if self.height() > _BAR_MIN_HEIGHT + 20:
                    self._detail.show()
                    self._detail.setMaximumHeight(self.height() - _BAR_MIN_HEIGHT - 10)
        else:
            self._answer_label.setText("⚠️ 题库未匹配")
            self._answer_label.setStyleSheet("color: #ff6666; font-size: 15px; font-weight: bold; border: none;")
            self._source_tag.setText("")

        if result.ai_answer and (not result.matches or result.matches[0]["score"] < 95):
            self._source_tag.setText("[AI]")
            self._answer_label.setText(f"🤖 {result.ai_answer}")
            self._answer_label.setStyleSheet("color: #00aaff; font-size: 15px; font-weight: bold; border: none;")

        self._question_preview.setText(result.question or "")

    # ── Drag ──
    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self._drag_pos = event.globalPosition().toPoint() - self.pos()

    def mouseMoveEvent(self, event):
        if self._drag_pos is not None:
            self.move(event.globalPosition().toPoint() - self._drag_pos)

    def mouseReleaseEvent(self, event):
        self._drag_pos = None

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if self.height() > _BAR_MIN_HEIGHT + 20:
            self._detail.show()
            self._detail.setMaximumHeight(self.height() - _BAR_MIN_HEIGHT - 10)
        else:
            self._detail.setMaximumHeight(0)
            self._detail.hide()


def _make_eyedropper_cursor() -> QCursor:
    """Create a custom eyedropper cursor pixmap."""
    pm = QPixmap(24, 24)
    pm.fill(QColor(0, 0, 0, 0))
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    # Pipette body
    p.setPen(QPen(QColor(255, 255, 255), 1.5))
    p.setBrush(QColor(60, 60, 60))
    # Draw a simple dropper shape: tube + bulb
    from PyQt6.QtGui import QPolygonF
    from PyQt6.QtCore import QPointF
    # Bulb (top circle)
    p.setBrush(QColor(180, 180, 180))
    p.drawEllipse(QPointF(7, 7), 5, 5)
    # Tube (diagonal line going down-right)
    p.setPen(QPen(QColor(200, 200, 200), 2.5))
    p.drawLine(int(7), int(12), int(20), int(22))
    # Tip highlight
    p.setPen(QPen(QColor(255, 255, 255), 1))
    p.drawLine(int(6), int(5), int(6), int(8))
    p.end()
    return QCursor(pm, 7, 7)


class ColorPicker(QWidget):
    """Screen color picker (eyedropper). Click to pick a color from the screen.

    Receives a pre-grabbed screenshot so it never calls grabWindow while
    the overlay is visible (which causes crashes).
    """

    color_picked = None  # set as pyqtSignal in __init__

    def __init__(self, screenshot: QPixmap, parent=None):
        super().__init__(parent)
        from PyQt6.QtCore import pyqtSignal
        ColorPicker.color_picked = pyqtSignal(str)

        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setCursor(_make_eyedropper_cursor())

        screen = QApplication.primaryScreen().geometry()
        self.setGeometry(screen)

        self._screenshot = screenshot
        self._preview_size = 60

    def _color_at_cursor(self) -> QColor:
        if self._screenshot is None:
            return QColor(0, 0, 0)
        pos = QCursor.pos()
        dpr = QApplication.primaryScreen().devicePixelRatio()
        x = int(pos.x() * dpr)
        y = int(pos.y() * dpr)
        img = self._screenshot.toImage()
        if 0 <= x < img.width() and 0 <= y < img.height():
            return QColor(img.pixel(x, y))
        return QColor(0, 0, 0)

    def paintEvent(self, event):
        if self._screenshot is None:
            return
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor(0, 0, 0, 30))

        color = self._color_at_cursor()
        pos = QCursor.pos()
        cx, cy = pos.x(), pos.y()
        r = self._preview_size // 2

        # Preview swatch
        painter.setPen(QPen(QColor(255, 255, 255), 2))
        painter.setBrush(color)
        painter.drawEllipse(cx - r - 30, cy - r - 30, self._preview_size, self._preview_size)

        # Hex label
        painter.setPen(QColor(255, 255, 255))
        painter.setFont(QFont("Consolas", 12, QFont.Weight.Bold))
        painter.drawText(cx - r - 30, cy - r - 38, color.name())

        # Instruction
        painter.setFont(QFont("Microsoft YaHei", 10))
        painter.drawText(cx - r - 30, cy + r + 20, "点击取色 | ESC取消")

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            color = self._color_at_cursor()
            self.color_picked.emit(color.name())
            self.close()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self.close()

    def mouseMoveEvent(self, event):
        self.update()

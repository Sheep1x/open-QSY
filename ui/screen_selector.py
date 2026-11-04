import logging

import cv2
import numpy as np
from PyQt6.QtCore import Qt, QRect, QPoint, pyqtSignal, QThread
from PyQt6.QtGui import QPainter, QPen, QColor, QCursor
from PyQt6.QtWidgets import QWidget, QApplication, QSizeGrip

try:
    import mss  # fast screen capture (~3-5x faster than PIL ImageGrab)
    _HAS_MSS = True
except ImportError:
    from PIL import ImageGrab
    _HAS_MSS = False

from core.camera import detect_change
from ui.overlay import is_transparent_color

logger = logging.getLogger(__name__)


class ScreenSelector(QWidget):
    """Transparent overlay for selecting a screen region.

    Phase 1: User draws rectangle (click + drag)
    Phase 2: Border-only marker, mouse-passthrough, stays on top
    """
    area_confirmed = pyqtSignal(object)  # emits QRect

    def __init__(self, parent=None, border_color: str = "#00ff88"):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
            | Qt.WindowType.Tool
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating)

        screen = QApplication.primaryScreen().geometry()
        self._screen_geo = screen
        self.setGeometry(screen)

        self._border_color = border_color
        self._drawing = False
        self._start: QPoint | None = None
        self._end: QPoint | None = None
        self._confirmed = False
        self.setCursor(Qt.CursorShape.CrossCursor)

    def set_border_color(self, color: str):
        self._border_color = color
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        border = None
        if not is_transparent_color(self._border_color):
            border = QColor(self._border_color)
        if not self._confirmed:
            # drawing phase: light overlay
            painter.fillRect(self.rect(), QColor(0, 0, 0, 80))
            if self._start and self._end:
                rect = QRect(self._start, self._end).normalized()
                painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Clear)
                painter.fillRect(rect, Qt.GlobalColor.transparent)
                painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)
                if border:
                    painter.setPen(QPen(border, 3))
                    painter.drawRect(rect)
                painter.setPen(QColor("#ffffff"))
                painter.drawText(rect.topLeft() + QPoint(5, -8),
                                 f"{rect.width()} x {rect.height()}  —  释放确认")
        elif border:
            # confirmed phase: border only (invisible when transparent)
            painter.setPen(QPen(border, 2, Qt.PenStyle.DashLine))
            painter.drawRect(self.rect().adjusted(1, 1, -1, -1))

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and not self._confirmed:
            self._drawing = True
            self._start = event.pos()
            self._end = event.pos()
            self.update()

    def mouseMoveEvent(self, event):
        if self._drawing:
            self._end = event.pos()
            self.update()

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and self._drawing:
            self._drawing = False
            if self._start and self._end:
                rect = QRect(self._start, self._end).normalized()
                if rect.width() > 30 and rect.height() > 30:
                    self._confirm(rect)
                    return
            self._start = None
            self._end = None
            self.update()

    def _confirm(self, local_rect: QRect):
        self._confirmed = True
        # convert to screen coordinates and resize widget to selection
        geo = self.geometry()
        screen_rect = QRect(
            geo.x() + local_rect.x(),
            geo.y() + local_rect.y(),
            local_rect.width(),
            local_rect.height(),
        )
        self.setGeometry(screen_rect)
        # mouse passthrough — user can click through to apps below
        self.setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents)
        self.setCursor(Qt.CursorShape.ArrowCursor)
        self.area_confirmed.emit(screen_rect)

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Escape:
            self.close()


class ScreenCaptureThread(QThread):
    """Background thread: periodically grabs a screen region and checks for changes.

    Screen grab + change detection run off the main thread,
    emits frame_captured signal for downstream recognition.
    """
    frame_captured = pyqtSignal(object)  # emits np.ndarray

    def __init__(self):
        super().__init__()
        self._rect: QRect | None = None
        self._prev: np.ndarray | None = None
        self._running = False
        self._interval_ms = 3000
        self._change_threshold = 0.02

    def set_rect(self, rect: QRect):
        self._rect = rect
        self._prev = None

    def start_capture(self, interval_ms: int, change_threshold: float):
        self._interval_ms = interval_ms
        self._change_threshold = change_threshold
        self._prev = None
        self._running = True
        self.start()

    def stop_capture(self):
        self._running = False
        self.wait()
        self._prev = None

    def run(self):
        while self._running:
            if self._rect is not None:
                frame = self.grab()
                if frame is not None and frame.size > 0:
                    if detect_change(self._prev, frame, self._change_threshold):
                        self._prev = frame.copy()
                        self.frame_captured.emit(frame)
            self.msleep(self._interval_ms)

    def grab(self) -> np.ndarray | None:
        if self._rect is None:
            return None
        try:
            dpr = QApplication.primaryScreen().devicePixelRatio()
            x1 = int(self._rect.x() * dpr)
            y1 = int(self._rect.y() * dpr)
            x2 = int((self._rect.x() + self._rect.width()) * dpr)
            y2 = int((self._rect.y() + self._rect.height()) * dpr)
            if _HAS_MSS:
                with mss.mss() as sct:
                    monitor = {"left": x1, "top": y1, "width": x2 - x1, "height": y2 - y1}
                    shot = sct.grab(monitor)
                    # BGRA → BGR, contiguous for downstream cv2 operations
                    return np.ascontiguousarray(np.asarray(shot)[:, :, :3])
            img = ImageGrab.grab(bbox=(x1, y1, x2, y2))
            return np.array(img)[:, :, ::-1].copy()  # RGB→BGR
        except Exception as e:
            logger.error("Screen grab failed: %s", e)
            return None

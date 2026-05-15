import logging

import cv2
import numpy as np
from PyQt6.QtCore import Qt, QRect, QPoint
from PyQt6.QtGui import QImage, QPixmap, QPainter, QPen, QColor
from PyQt6.QtWidgets import QLabel

logger = logging.getLogger(__name__)


class CameraWidget(QLabel):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(640, 480)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setStyleSheet("background-color: #1a1a2e; border-radius: 8px;")
        self.setText("摄像头未启动\n拖拽画面可选择识别区域(ROI)")
        self.setToolTip("拖拽画面可选择识别区域(ROI)")

        self._roi: tuple | None = None
        self._drawing = False
        self._start_point: QPoint | None = None
        self._current_point: QPoint | None = None
        self._display_scale: float = 1.0
        self._display_offset: tuple = (0, 0)
        self._frame_size: tuple = (0, 0)

    def update_frame(self, frame: np.ndarray):
        h, w = frame.shape[:2]
        self._frame_size = (w, h)

        display_w, display_h = self.width(), self.height()
        scale_x = display_w / w
        scale_y = display_h / h
        self._display_scale = min(scale_x, scale_y)

        scaled_w = int(w * self._display_scale)
        scaled_h = int(h * self._display_scale)
        self._display_offset = ((display_w - scaled_w) // 2, (display_h - scaled_h) // 2)

        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        qimg = QImage(rgb.data, w, h, 3 * w, QImage.Format.Format_RGB888).copy()
        pixmap = QPixmap.fromImage(qimg).scaled(
            display_w, display_h, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation
        )

        canvas = QPixmap(display_w, display_h)
        canvas.fill(QColor("#1a1a2e"))
        painter = QPainter(canvas)
        painter.drawPixmap(self._display_offset[0], self._display_offset[1], pixmap)

        if self._roi:
            self._draw_roi(painter)

        if self._drawing and self._start_point and self._current_point:
            pen = QPen(QColor("#00ff88"), 2, Qt.PenStyle.DashLine)
            painter.setPen(pen)
            rect = QRect(self._start_point, self._current_point)
            painter.drawRect(rect)

        painter.end()
        self.setPixmap(canvas)

    def _draw_roi(self, painter: QPainter):
        x1, y1, x2, y2 = self._roi
        ox, oy = self._display_offset
        s = self._display_scale
        top_left = QPoint(int(x1 * s + ox), int(y1 * s + oy))
        bottom_right = QPoint(int(x2 * s + ox), int(y2 * s + oy))

        pen = QPen(QColor("#00ff88"), 2)
        painter.setPen(pen)
        painter.drawRect(QRect(top_left, bottom_right))

        overlay = QColor(0, 255, 136, 25)
        painter.fillRect(QRect(top_left, bottom_right), overlay)

    def set_roi(self, roi: tuple | None):
        self._roi = roi

    def get_roi(self) -> tuple | None:
        return self._roi

    def _display_to_frame(self, pos: QPoint) -> tuple[int, int]:
        ox, oy = self._display_offset
        s = self._display_scale
        if s == 0:
            return 0, 0
        fx = int((pos.x() - ox) / s)
        fy = int((pos.y() - oy) / s)
        w, h = self._frame_size
        fx = max(0, min(fx, w))
        fy = max(0, min(fy, h))
        return fx, fy

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self._drawing = True
            self._start_point = event.pos()
            self._current_point = event.pos()

    def mouseMoveEvent(self, event):
        if self._drawing:
            self._current_point = event.pos()

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton and self._drawing:
            self._drawing = False
            if self._start_point and self._current_point:
                p1 = self._display_to_frame(self._start_point)
                p2 = self._display_to_frame(self._current_point)
                x1, x2 = min(p1[0], p2[0]), max(p1[0], p2[0])
                y1, y2 = min(p1[1], p2[1]), max(p1[1], p2[1])
                if (x2 - x1) > 20 and (y2 - y1) > 20:
                    self._roi = (x1, y1, x2, y2)
                else:
                    self._roi = None

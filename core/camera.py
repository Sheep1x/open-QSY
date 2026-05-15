import logging
from typing import Callable

import cv2
import numpy as np
from PyQt6.QtCore import QThread, pyqtSignal

logger = logging.getLogger(__name__)

RESOLUTIONS = [
    (1920, 1080),
    (1600, 900),
    (1280, 720),
    (1024, 768),
    (800, 600),
]


class CameraThread(QThread):
    frame_ready = pyqtSignal(np.ndarray)
    error_occurred = pyqtSignal(str)

    def __init__(self, camera_index: int = 0):
        super().__init__()
        self.camera_index = camera_index
        self._running = False

    def run(self):
        self._running = True
        cap = cv2.VideoCapture(self.camera_index, cv2.CAP_DSHOW)
        if not cap.isOpened():
            cap = cv2.VideoCapture(self.camera_index)
        if not cap.isOpened():
            self.error_occurred.emit(f"无法打开摄像头 (索引: {self.camera_index})")
            return

        # try best resolution
        best_w, best_h = 640, 480
        for w, h in RESOLUTIONS:
            cap.set(cv2.CAP_PROP_FRAME_WIDTH, w)
            cap.set(cv2.CAP_PROP_FRAME_HEIGHT, h)
            actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            if actual_w >= w and actual_h >= h:
                best_w, best_h = actual_w, actual_h
                break
            elif actual_w > best_w:
                best_w, best_h = actual_w, actual_h

        # re-apply best
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, best_w)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, best_h)
        logger.info("Camera resolution: %dx%d", best_w, best_h)

        while self._running:
            ret, frame = cap.read()
            if not ret:
                self.error_occurred.emit("摄像头读取失败")
                break
            self.frame_ready.emit(frame)
            self.msleep(33)  # ~30fps
        cap.release()

    def stop(self):
        self._running = False
        self.wait()


def list_cameras(max_test: int = 10) -> list[dict]:
    cameras = []
    seen_names = set()
    for i in range(max_test):
        backends = [cv2.CAP_DSHOW, cv2.CAP_ANY]
        for backend in backends:
            cap = cv2.VideoCapture(i, backend)
            if cap.isOpened():
                w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                name = cap.getBackendName()
                label = f"摄像头 {i} ({w}x{h}) [{name}]"
                if label not in seen_names:
                    seen_names.add(label)
                    cameras.append({"index": i, "name": label})
                cap.release()
                break
    if not cameras:
        # fallback: try CAP_DSHOW with direct read
        for i in range(max_test):
            cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)
            if cap.isOpened():
                ret, _ = cap.read()
                if ret:
                    cameras.append({"index": i, "name": f"摄像头 {i}"})
                cap.release()
    return cameras


def crop_roi(frame: np.ndarray, roi: tuple | None) -> np.ndarray:
    if roi is None:
        return frame
    x1, y1, x2, y2 = roi
    h, w = frame.shape[:2]
    x1, x2 = max(0, int(x1)), min(w, int(x2))
    y1, y2 = max(0, int(y1)), min(h, int(y2))
    return frame[y1:y2, x1:x2]


def detect_change(prev: np.ndarray | None, curr: np.ndarray, threshold: float = 0.02) -> bool:
    if prev is None:
        return True
    prev_resized = cv2.resize(prev, (160, 120))
    curr_resized = cv2.resize(curr, (160, 120))
    prev_gray = cv2.cvtColor(prev_resized, cv2.COLOR_BGR2GRAY)
    curr_gray = cv2.cvtColor(curr_resized, cv2.COLOR_BGR2GRAY)
    diff = cv2.absdiff(prev_gray, curr_gray)
    _, binary = cv2.threshold(diff, 30, 255, cv2.THRESH_BINARY)
    change_ratio = np.count_nonzero(binary) / binary.size
    return change_ratio > threshold

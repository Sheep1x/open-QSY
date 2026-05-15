import logging
import sys
import threading
from pathlib import Path

import cv2
import numpy as np
from PyQt6.QtCore import Qt, QThread, pyqtSignal, QTimer, QRect
from PyQt6.QtGui import QFont, QColor, QIcon
from PyQt6.QtWidgets import (
    QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QSplitter,
    QLabel, QPushButton, QComboBox, QLineEdit, QGroupBox,
    QFileDialog, QListWidget, QListWidgetItem, QCheckBox,
    QSpinBox, QDoubleSpinBox, QMessageBox, QStatusBar,
    QTabWidget, QTextEdit, QSlider, QFormLayout,
)

from core.camera import CameraThread, list_cameras
from utils.resources import icon_path
from core.config import load_config, save_config, get_default_config
from core.matcher import Matcher
from core.quiz_bank import QuizBank
from core.hotkey_manager import HotkeyManager
from core.vision_engine import VisionEngine
from ui.camera_widget import CameraWidget
from ui.screen_selector import ScreenSelector, ScreenCaptureThread
from utils.file_parser import detect_columns

logger = logging.getLogger(__name__)

STYLESHEET = """
QMainWindow { background-color: #0f0f23; }
QLabel { color: #e0e0e0; }
QGroupBox {
    color: #00ff88;
    border: 1px solid #2a2a4a;
    border-radius: 6px;
    margin-top: 8px;
    padding-top: 16px;
    font-weight: bold;
}
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
QPushButton {
    background-color: #1a1a3e;
    color: #e0e0e0;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    padding: 6px 14px;
    font-size: 13px;
}
QPushButton:hover { background-color: #2a2a5e; border-color: #00ff88; }
QPushButton:pressed { background-color: #00ff88; color: #0f0f23; }
QPushButton:disabled { background-color: #111; color: #555; border-color: #333; }
QLineEdit, QSpinBox, QDoubleSpinBox {
    background-color: #1a1a3e;
    color: #e0e0e0;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    padding: 4px 8px;
}
QLineEdit:focus { border-color: #00ff88; }
QComboBox {
    background-color: #1a1a3e;
    color: #e0e0e0;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    padding: 4px 8px;
}
QComboBox:focus { border-color: #00ff88; }
QComboBox QAbstractItemView {
    background-color: #1a1a3e;
    color: #e0e0e0;
    selection-background-color: #2a2a5e;
    selection-color: #00ff88;
    border: 1px solid #2a2a4a;
}
QComboBox QLineEdit {
    background-color: #1a1a3e;
    color: #e0e0e0;
}
QListWidget {
    background-color: #1a1a3e;
    color: #e0e0e0;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
}
QListWidget::item { padding: 4px; }
QListWidget::item:selected { background-color: #2a2a5e; }
QTextEdit {
    background-color: #1a1a3e;
    color: #e0e0e0;
    border: 1px solid #2a2a4a;
    border-radius: 4px;
    padding: 4px;
    font-size: 14px;
}
QCheckBox { color: #e0e0e0; }
QCheckBox::indicator { width: 16px; height: 16px; }
QSlider::groove:horizontal {
    height: 6px;
    background: #2a2a4a;
    border-radius: 3px;
}
QSlider::handle:horizontal {
    width: 14px; height: 14px;
    margin: -4px 0;
    background: #00ff88;
    border-radius: 7px;
}
QSplitter::handle { background-color: #2a2a4a; }
QTabWidget::pane { border: 1px solid #2a2a4a; border-radius: 4px; }
QTabBar::tab {
    background-color: #1a1a3e;
    color: #e0e0e0;
    padding: 6px 16px;
    border: 1px solid #2a2a4a;
    border-bottom: none;
    border-top-left-radius: 4px;
    border-top-right-radius: 4px;
}
QTabBar::tab:selected { background-color: #2a2a5e; color: #00ff88; }
QMessageBox { background-color: #1a1a3e; }
QMessageBox QLabel { color: #e0e0e0; font-size: 14px; }
QMessageBox QPushButton { min-width: 80px; }
"""


class RecognizeThread(QThread):
    """Background thread for camera real-time recognition."""
    result_ready = pyqtSignal(object)
    error_occurred = pyqtSignal(str)

    def __init__(self, matcher: Matcher):
        super().__init__()
        self.matcher = matcher
        self._running = False
        self._frame: np.ndarray | None = None
        self._roi: tuple | None = None
        self._interval: float = 3.0
        self._change_threshold: float = 0.02
        self._lock = threading.Lock()

    def set_frame(self, frame: np.ndarray, roi: tuple | None):
        with self._lock:
            self._frame = frame
            self._roi = roi

    def run(self):
        self._running = True
        while self._running:
            with self._lock:
                frame = self._frame
                roi = self._roi
            if frame is not None:
                try:
                    result = self.matcher.process_frame(frame.copy(), roi, self._change_threshold)
                    if result is not None:
                        self.result_ready.emit(result)
                except Exception as e:
                    self.error_occurred.emit(str(e))
            self.msleep(int(self._interval * 1000))

    def stop(self):
        self._running = False
        self.wait(3000)


class ScreenRecognizeThread(QThread):
    """Background thread for screen capture real-time recognition."""
    result_ready = pyqtSignal(object)
    error_occurred = pyqtSignal(str)

    def __init__(self, matcher: Matcher):
        super().__init__()
        self.matcher = matcher
        self._running = False
        self._frame: np.ndarray | None = None
        self._lock = threading.Lock()

    def set_frame(self, frame: np.ndarray):
        with self._lock:
            self._frame = frame

    def run(self):
        self._running = True
        while self._running:
            with self._lock:
                frame = self._frame
                self._frame = None
            if frame is not None:
                try:
                    result = self.matcher.process_image(frame)
                    if result is not None:
                        self.result_ready.emit(result)
                except Exception as e:
                    self.error_occurred.emit(str(e))
            self.msleep(100)

    def stop(self):
        self._running = False
        self.wait(3000)


class SingleShotRecognizeThread(QThread):
    """One-shot background recognition for single capture."""
    result_ready = pyqtSignal(object)
    error_occurred = pyqtSignal(str)

    def __init__(self, matcher: Matcher, frame: np.ndarray, roi: tuple | None = None):
        super().__init__()
        self.matcher = matcher
        self._frame = frame
        self._roi = roi

    def run(self):
        try:
            result = self.matcher.process_image(self._frame, self._roi)
            self.result_ready.emit(result)
        except Exception as e:
            self.error_occurred.emit(str(e))


class ApiTestThread(QThread):
    """Background thread for API connection test."""
    success = pyqtSignal(str)
    failed = pyqtSignal(str)

    def __init__(self, base_url: str, api_key: str, model: str):
        super().__init__()
        self._base_url = base_url
        self._api_key = api_key
        self._model = model

    def run(self):
        try:
            engine = VisionEngine(self._base_url, self._api_key, self._model)
            resp = engine.client.chat.completions.create(
                model=self._model,
                messages=[{"role": "user", "content": "Hi"}],
                max_tokens=10,
            )
            self.success.emit(f"API 连接正常\n模型: {resp.model}")
        except Exception as e:
            self.failed.emit(f"API 连接失败:\n{e}")


class CameraScanThread(QThread):
    """Background thread for scanning available cameras."""
    cameras_found = pyqtSignal(list)

    def run(self):
        cameras = list_cameras()
        self.cameras_found.emit(cameras)


def _build_result_panel() -> tuple[QWidget, dict]:
    """Build a shared result panel (question + answer + matches + ai)."""
    widgets = {}
    panel = QWidget()
    layout = QVBoxLayout(panel)
    layout.setContentsMargins(0, 0, 0, 0)

    widgets["status"] = QLabel("状态: 未启动")
    widgets["status"].setStyleSheet("color: #ffaa00; font-size: 13px;")
    layout.addWidget(widgets["status"])

    layout.addWidget(QLabel("识别题目:"))
    widgets["question"] = QTextEdit()
    widgets["question"].setReadOnly(True)
    widgets["question"].setMaximumHeight(140)
    widgets["question"].setPlaceholderText("识别到的题目将显示在这里...")
    layout.addWidget(widgets["question"])

    layout.addWidget(QLabel("匹配答案:"))
    widgets["answer"] = QTextEdit()
    widgets["answer"].setReadOnly(True)
    widgets["answer"].setMaximumHeight(100)
    widgets["answer"].setPlaceholderText("匹配到的答案将显示在这里...")
    widgets["answer"].setStyleSheet("QTextEdit { font-size: 22px; font-weight: bold; }")
    layout.addWidget(widgets["answer"])

    layout.addWidget(QLabel("题库原题:"))
    widgets["matches"] = QListWidget()
    widgets["matches"].setMaximumHeight(200)
    layout.addWidget(widgets["matches"])

    layout.addWidget(QLabel("AI辅助答案:"))
    widgets["ai"] = QTextEdit()
    widgets["ai"].setReadOnly(True)
    widgets["ai"].setMaximumHeight(80)
    widgets["ai"].setPlaceholderText("AI辅助答案（题库未匹配时）")
    layout.addWidget(widgets["ai"])

    layout.addStretch()
    return panel, widgets


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("题眼实时识别答题助手")
        self.setWindowIcon(QIcon(icon_path()))
        self.setMinimumSize(1100, 750)
        self.setStyleSheet(STYLESHEET)

        self.config = load_config()
        self.quiz_bank = QuizBank()
        self.vision: VisionEngine | None = None
        self.matcher: Matcher | None = None

        # camera
        self.camera_thread: CameraThread | None = None
        self.recognize_thread: RecognizeThread | None = None
        self._latest_frame: np.ndarray | None = None
        self._is_cam_recognizing = False

        # screen capture
        self._scr_rect: QRect | None = None
        self._scr_selector: ScreenSelector | None = None
        self._scr_capture = ScreenCaptureThread()
        self._scr_recognize_thread: ScreenRecognizeThread | None = None
        self._is_screen_capturing = False
        self._scr_single_shot: SingleShotRecognizeThread | None = None
        self._cam_single_shot: SingleShotRecognizeThread | None = None

        # background threads for API test and camera scan
        self._api_test_thread: ApiTestThread | None = None
        self._camera_scan_thread: CameraScanThread | None = None

        # frame timer (created once, reused)
        self._frame_timer = QTimer()
        self._frame_timer.timeout.connect(self._feed_frame)

        # hotkeys (init after UI so callbacks can reference widgets)
        self._hotkey = HotkeyManager()

        self._init_ui()
        self._load_config_to_ui()
        self._load_bank_cache()
        self._setup_hotkeys()

    # ────────────────────── UI ──────────────────────

    def _init_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QHBoxLayout(central)
        main_layout.setContentsMargins(8, 8, 8, 8)
        main_layout.setSpacing(8)

        tabs = QTabWidget()

        # ━━━━━━ Tab 1: 摄像头识别 ━━━━━━
        cam_tab = QWidget()
        cam_layout = QHBoxLayout(cam_tab)

        # left: camera preview
        cam_left = QWidget()
        cam_left_layout = QVBoxLayout(cam_left)
        cam_left_layout.setContentsMargins(0, 0, 0, 0)

        self.camera_widget = CameraWidget()
        cam_left_layout.addWidget(self.camera_widget, stretch=1)

        cam_ctrl = QHBoxLayout()
        self.btn_cam_refresh = QPushButton("刷新摄像头")
        self.btn_cam_refresh.clicked.connect(self._refresh_cameras)
        self.combo_camera = QComboBox()
        self.combo_camera.setMinimumWidth(150)
        self.combo_camera.setEditable(True)
        self.combo_camera.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        self.combo_camera.lineEdit().setPlaceholderText("选择或输入摄像头索引")
        self.btn_cam_start = QPushButton("开启摄像头")
        self.btn_cam_start.clicked.connect(self._toggle_camera)
        self.btn_roi_clear = QPushButton("清除ROI")
        self.btn_roi_clear.clicked.connect(self._clear_roi)
        cam_ctrl.addWidget(self.btn_cam_refresh)
        cam_ctrl.addWidget(self.combo_camera, stretch=1)
        cam_ctrl.addWidget(self.btn_cam_start)
        cam_ctrl.addWidget(self.btn_roi_clear)
        cam_left_layout.addLayout(cam_ctrl)

        cam_btn_row = QHBoxLayout()
        self.btn_cam_start_recog = QPushButton("开始实时识别")
        self.btn_cam_start_recog.setMinimumHeight(36)
        self.btn_cam_start_recog.setStyleSheet("QPushButton{font-size:14px;font-weight:bold;}")
        self.btn_cam_start_recog.clicked.connect(self._toggle_cam_recognize)
        self.btn_cam_trigger = QPushButton("立即识别一次")
        self.btn_cam_trigger.setMinimumHeight(36)
        self.btn_cam_trigger.setStyleSheet("QPushButton{background-color:#2a2a5e;border-color:#00aaff;font-size:14px;font-weight:bold;}")
        self.btn_cam_trigger.clicked.connect(self._cam_trigger_once)
        cam_btn_row.addWidget(self.btn_cam_start_recog)
        cam_btn_row.addWidget(self.btn_cam_trigger)
        cam_left_layout.addLayout(cam_btn_row)

        cam_layout.addWidget(cam_left, stretch=3)

        # right: camera results
        cam_result_panel, self.cam_w = _build_result_panel()
        cam_layout.addWidget(cam_result_panel, stretch=2)

        tabs.addTab(cam_tab, "摄像头识别")

        # ━━━━━━ Tab 2: 框选识别 ━━━━━━
        scr_tab = QWidget()
        scr_layout = QVBoxLayout(scr_tab)

        scr_ctrl = QHBoxLayout()
        self.btn_scr_select = QPushButton("框选屏幕区域 (Ctrl+Alt+A)")
        self.btn_scr_select.setStyleSheet("QPushButton{background-color:#2a2a5e;border-color:#00ff88;}")
        self.btn_scr_select.clicked.connect(self._scr_select_area)
        self.btn_scr_start = QPushButton("开始实时识别 (Ctrl+Alt+D)")
        self.btn_scr_start.setStyleSheet("QPushButton{font-size:14px;font-weight:bold;}")
        self.btn_scr_start.clicked.connect(self._toggle_screen_capture)
        self.btn_scr_trigger = QPushButton("立即识别一次 (Ctrl+Alt+S)")
        self.btn_scr_trigger.setMinimumHeight(40)
        self.btn_scr_trigger.setStyleSheet("QPushButton{background-color:#2a2a5e;border-color:#00aaff;font-size:14px;font-weight:bold;}")
        self.btn_scr_trigger.clicked.connect(self._scr_capture_once)
        self.lbl_scr_status = QLabel("未框选区域")
        self.lbl_scr_status.setStyleSheet("color:#888;font-size:12px;")
        scr_ctrl.addWidget(self.btn_scr_select)
        scr_ctrl.addWidget(self.btn_scr_start)
        scr_ctrl.addWidget(self.btn_scr_trigger)
        scr_ctrl.addWidget(self.lbl_scr_status, stretch=1)
        scr_layout.addLayout(scr_ctrl)

        # screen capture results
        scr_result_panel, self.scr_w = _build_result_panel()
        scr_layout.addWidget(scr_result_panel)

        tabs.addTab(scr_tab, "框选识别")

        # ━━━━━━ Tab 3: 题库管理 ━━━━━━
        quiz_tab = QWidget()
        quiz_layout = QVBoxLayout(quiz_tab)

        import_group = QGroupBox("导入题库")
        import_form = QFormLayout(import_group)
        self.btn_import = QPushButton("选择文件 (Excel/CSV)")
        self.btn_import.clicked.connect(self._import_quiz)
        self.lbl_file = QLabel("未选择文件")
        self.lbl_file.setStyleSheet("color: #888;")
        self.combo_question_col = QComboBox()
        self.combo_answer_col = QComboBox()
        import_form.addRow(self.btn_import)
        import_form.addRow("文件:", self.lbl_file)
        import_form.addRow("题目列:", self.combo_question_col)
        import_form.addRow("答案列:", self.combo_answer_col)
        self.btn_do_import = QPushButton("导入")
        self.btn_do_import.clicked.connect(self._do_import)
        import_form.addRow(self.btn_do_import)
        quiz_layout.addWidget(import_group)

        bank_group = QGroupBox("题库状态")
        bank_layout = QVBoxLayout(bank_group)
        self.lbl_bank_count = QLabel("题目数: 0")
        bank_layout.addWidget(self.lbl_bank_count)
        self.btn_bank_clear = QPushButton("清空题库")
        self.btn_bank_clear.clicked.connect(self._clear_bank)
        bank_layout.addWidget(self.btn_bank_clear)
        self.btn_bank_save = QPushButton("保存题库")
        self.btn_bank_save.setStyleSheet("QPushButton{background-color:#2a2a5e;border-color:#00ff88;font-weight:bold;}")
        self.btn_bank_save.clicked.connect(self._save_bank)
        bank_layout.addWidget(self.btn_bank_save)
        quiz_layout.addWidget(bank_group)

        quiz_layout.addStretch()
        tabs.addTab(quiz_tab, "题库管理")

        # ━━━━━━ Tab 4: 设置 ━━━━━━
        settings_tab = QWidget()
        settings_layout = QVBoxLayout(settings_tab)

        api_group = QGroupBox("API 配置")
        api_form = QFormLayout(api_group)
        self.edit_base_url = QLineEdit()
        self.edit_base_url.setPlaceholderText("https://api.openai.com/v1")
        self.edit_api_key = QLineEdit()
        self.edit_api_key.setEchoMode(QLineEdit.EchoMode.Password)
        self.edit_api_key.setPlaceholderText("sk-...")
        self.edit_model = QLineEdit()
        self.edit_model.setPlaceholderText("gpt-4o")
        self.btn_save_config = QPushButton("保存配置")
        self.btn_save_config.clicked.connect(self._save_config)
        self.btn_test_api = QPushButton("测试连接")
        self.btn_test_api.clicked.connect(self._test_api)
        api_form.addRow("Base URL:", self.edit_base_url)
        api_form.addRow("API Key:", self.edit_api_key)
        api_form.addRow("Model:", self.edit_model)
        api_form.addRow(self.btn_save_config)
        api_form.addRow(self.btn_test_api)
        settings_layout.addWidget(api_group)

        recog_group = QGroupBox("识别设置")
        recog_form = QFormLayout(recog_group)
        self.spin_interval = QDoubleSpinBox()
        self.spin_interval.setRange(0.5, 30.0)
        self.spin_interval.setSingleStep(0.5)
        self.spin_interval.setSuffix(" 秒")
        self.spin_threshold = QSpinBox()
        self.spin_threshold.setRange(0, 100)
        self.spin_threshold.setSuffix(" %")
        self.spin_change = QDoubleSpinBox()
        self.spin_change.setRange(0.001, 0.5)
        self.spin_change.setSingleStep(0.005)
        self.spin_change.setDecimals(3)
        self.chk_ai_answer = QCheckBox("AI辅助答题（题库未匹配时自动调用）")
        self.chk_parallel = QCheckBox("并行AI答题（与题库搜索同时执行，更快但额外消耗API配额）")
        self.chk_local_ocr = QCheckBox("本地OCR优先（RapidOCR → GPT Vision，更快且免费）")
        recog_form.addRow("识别间隔:", self.spin_interval)
        recog_form.addRow("匹配阈值:", self.spin_threshold)
        recog_form.addRow("变化检测灵敏度:", self.spin_change)
        recog_form.addRow(self.chk_ai_answer)
        recog_form.addRow(self.chk_parallel)
        recog_form.addRow(self.chk_local_ocr)
        settings_layout.addWidget(recog_group)

        dev_label = QLabel("开发者: 孙启然 / 杨一旭")
        dev_label.setStyleSheet("color: #555; font-size: 11px;")
        dev_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        settings_layout.addWidget(dev_label)

        settings_layout.addStretch()
        tabs.addTab(settings_tab, "设置")

        main_layout.addWidget(tabs)

        # Status bar
        self.statusBar().showMessage("就绪")
        self.statusBar().setStyleSheet("color: #888;")

    # ────────────────────── Config ──────────────────────

    def _load_config_to_ui(self):
        c = self.config
        self.edit_base_url.setText(c.get("base_url", ""))
        self.edit_api_key.setText(c.get("api_key", ""))
        self.edit_model.setText(c.get("model", ""))
        # Migrate old defaults: if config has never been saved with new keys, use new defaults
        if "enable_ai_answer" not in c:
            c["frame_interval"] = 1.0
            c["match_threshold"] = 70
        self.spin_interval.setValue(c.get("frame_interval", 1.0))
        self.spin_threshold.setValue(c.get("match_threshold", 70))
        self.spin_change.setValue(c.get("change_threshold", 0.02))
        self.chk_ai_answer.setChecked(c.get("enable_ai_answer", True))
        self.chk_parallel.setChecked(c.get("parallel_ai_answer", False))
        self.chk_local_ocr.setChecked(c.get("use_local_ocr", True))
        # defer camera scanning to avoid blocking startup
        self._refresh_cameras()
        idx = c.get("camera_index", 0)
        for i in range(self.combo_camera.count()):
            if self.combo_camera.itemData(i) == idx:
                self.combo_camera.setCurrentIndex(i)
                break

    def _save_config(self):
        self.config["base_url"] = self.edit_base_url.text().strip()
        self.config["api_key"] = self.edit_api_key.text().strip()
        self.config["model"] = self.edit_model.text().strip()
        self.config["frame_interval"] = self.spin_interval.value()
        self.config["match_threshold"] = self.spin_threshold.value()
        self.config["change_threshold"] = self.spin_change.value()
        self.config["enable_ai_answer"] = self.chk_ai_answer.isChecked()
        self.config["parallel_ai_answer"] = self.chk_parallel.isChecked()
        self.config["use_local_ocr"] = self.chk_local_ocr.isChecked()
        cam_idx = self.combo_camera.currentData()
        if cam_idx is not None:
            self.config["camera_index"] = cam_idx
        save_config(self.config)
        self.statusBar().showMessage("配置已保存", 3000)

    # ────────────────────── Shared ──────────────────────

    def _msg_box(self, icon: QMessageBox.Icon, title: str, text: str) -> QMessageBox:
        msg = QMessageBox(self)
        msg.setWindowIcon(QIcon(icon_path()))
        msg.setIcon(icon)
        msg.setWindowTitle(title)
        msg.setText(text)
        return msg

    def _ensure_vision(self) -> bool:
        if self.vision is None or self.matcher is None:
            base_url = self.edit_base_url.text().strip()
            api_key = self.edit_api_key.text().strip()
            model = self.edit_model.text().strip()
            if not base_url or not api_key or not model:
                self._msg_box(QMessageBox.Icon.Warning, "提示", "请先在设置中配置 API").exec()
                return False
            self.vision = VisionEngine(base_url, api_key, model)
            self.matcher = Matcher(self.vision, self.quiz_bank)
        # always sync latest settings
        self.matcher.match_threshold = self.spin_threshold.value()
        self.matcher.enable_ai_answer = self.chk_ai_answer.isChecked()
        self.matcher.parallel_ai_answer = self.chk_parallel.isChecked()
        self.matcher.use_local_ocr = self.chk_local_ocr.isChecked()
        return True

    def _setup_hotkeys(self):
        self._hotkey.register("ctrl+alt+s", self._scr_capture_once)
        self._hotkey.register("ctrl+alt+a", self._scr_select_area)
        self._hotkey.register("ctrl+alt+d", self._toggle_screen_capture)

    def _show_result(self, w: dict, result):
        if result.empty:
            w["status"].setText("状态: 未检测到题目")
            w["status"].setStyleSheet("color: #ffaa00; font-size: 13px;")
            return

        w["status"].setText(f"状态: 已识别 ({result.latency:.1f}s)")
        w["status"].setStyleSheet("color: #00ff88; font-size: 13px;")

        w["question"].setText(result.question)

        def _fmt_answer(answer: str, options: dict) -> str:
            """Return answer with option content if available."""
            if not answer:
                return "（无答案）"
            # Single-letter answer (A/B/C/D/E) with option content
            if len(answer) == 1 and answer.upper() in options:
                return f"{answer}. {options[answer.upper()]}"
            # Multi-letter answer (ABCD) with option contents
            if len(answer) > 1 and all(c.upper() in options for c in answer):
                parts = [f"{c}. {options[c.upper()]}" for c in answer]
                return "  ".join(parts)
            return answer

        w["matches"].clear()
        w["answer"].clear()
        if result.matches:
            best = result.matches[0]
            best_opts = best.get("options", {})
            if best["answer"]:
                ans_text = _fmt_answer(best["answer"], best_opts)
                w["answer"].setText(f"✅ {ans_text}  (匹配度 {best['score']:.0f}%)")
            else:
                w["answer"].setText("（题库无此题答案）")
            for m in result.matches:
                score = m["score"]
                q = m["question"]
                opts = m.get("options", {})
                a = _fmt_answer(m["answer"], opts)
                item = QListWidgetItem()
                if score >= 95:
                    item.setForeground(QColor("#00ff88"))
                elif score >= 80:
                    item.setForeground(QColor("#ffaa00"))
                else:
                    item.setForeground(QColor("#ff6666"))
                item.setText(f"[{score:.0f}%] {q}\n    答案: {a}")
                item.setData(Qt.ItemDataRole.UserRole, m)
                w["matches"].addItem(item)
        else:
            w["answer"].setText("⚠️ 题库未匹配到答案")

        if result.ai_answer:
            w["ai"].setText(result.ai_answer)
        else:
            w["ai"].clear()

    def _cleanup_single_shot(self, attr_name: str, thread: SingleShotRecognizeThread):
        """Clean up a finished single-shot thread and re-enable buttons."""
        if getattr(self, attr_name, None) is thread:
            setattr(self, attr_name, None)
        if attr_name == "_cam_single_shot":
            self.btn_cam_trigger.setEnabled(True)
            self.btn_cam_trigger.setText("立即识别一次")
        elif attr_name == "_scr_single_shot":
            self.btn_scr_trigger.setEnabled(True)
            self.btn_scr_trigger.setText("立即识别一次 (Ctrl+Alt+S)")

    # ────────────────────── Camera ──────────────────────

    def _refresh_cameras(self):
        self.btn_cam_refresh.setEnabled(False)
        self.btn_cam_refresh.setText("扫描中...")
        self.combo_camera.clear()
        self.combo_camera.addItem("扫描中...", -1)
        self._camera_scan_thread = CameraScanThread()
        self._camera_scan_thread.cameras_found.connect(self._on_cameras_found)
        self._camera_scan_thread.start()

    def _on_cameras_found(self, cameras: list):
        self.combo_camera.clear()
        for cam in cameras:
            self.combo_camera.addItem(cam["name"], cam["index"])
        if not cameras:
            self.combo_camera.addItem("未检测到摄像头", -1)
        self.btn_cam_refresh.setEnabled(True)
        self.btn_cam_refresh.setText("刷新摄像头")
        # restore saved camera index
        idx = self.config.get("camera_index", 0)
        for i in range(self.combo_camera.count()):
            if self.combo_camera.itemData(i) == idx:
                self.combo_camera.setCurrentIndex(i)
                break

    def _toggle_camera(self):
        if self.camera_thread and self.camera_thread.isRunning():
            self.camera_thread.stop()
            self.camera_thread = None
            self.btn_cam_start.setText("开启摄像头")
            self.camera_widget.setText("摄像头已关闭")
            self._latest_frame = None
            return

        idx = self.combo_camera.currentData()
        if idx is None or idx < 0:
            text = self.combo_camera.currentText().strip()
            try:
                idx = int(text.split()[0])
            except (ValueError, IndexError):
                self._msg_box(QMessageBox.Icon.Warning, "提示", "请选择或输入有效的摄像头索引\n例如: 0、1、2").exec()
                return

        self.camera_thread = CameraThread(idx)
        self.camera_thread.frame_ready.connect(self._on_frame)
        self.camera_thread.error_occurred.connect(lambda e: self.statusBar().showMessage(e, 5000))
        self.camera_thread.start()
        self.btn_cam_start.setText("关闭摄像头")

    def _on_frame(self, frame: np.ndarray):
        self._latest_frame = frame
        roi = self.camera_widget.get_roi()
        self.camera_widget.update_frame(frame)

    def _clear_roi(self):
        self.camera_widget.set_roi(None)

    def _toggle_cam_recognize(self):
        if self._is_cam_recognizing:
            self._stop_cam_recognize()
            return

        if not self._ensure_vision():
            return
        if self._latest_frame is None:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请先开启摄像头").exec()
            return

        self.recognize_thread = RecognizeThread(self.matcher)
        self.recognize_thread._interval = self.spin_interval.value()
        self.recognize_thread._change_threshold = self.spin_change.value()
        self.recognize_thread.result_ready.connect(lambda r: self._show_result(self.cam_w, r))
        self.recognize_thread.error_occurred.connect(self._on_cam_error)
        self.recognize_thread.start()

        self._is_cam_recognizing = True
        self.btn_cam_start_recog.setText("停止实时识别")
        self.btn_cam_start_recog.setStyleSheet(
            "QPushButton{font-size:14px;font-weight:bold;background-color:#ff4444;color:white;}"
        )
        self.cam_w["status"].setText("状态: 识别中...")
        self.cam_w["status"].setStyleSheet("color: #00ff88; font-size: 13px;")

        self._frame_timer.start(500)

    def _stop_cam_recognize(self):
        if self.recognize_thread:
            self.recognize_thread._running = False
            self.recognize_thread = None
        self._frame_timer.stop()
        self._is_cam_recognizing = False
        self.btn_cam_start_recog.setText("开始实时识别")
        self.btn_cam_start_recog.setStyleSheet("QPushButton{font-size:14px;font-weight:bold;}")
        self.cam_w["status"].setText("状态: 已停止")
        self.cam_w["status"].setStyleSheet("color: #ffaa00; font-size: 13px;")

    def _feed_frame(self):
        if self._latest_frame is not None and self.recognize_thread:
            roi = self.camera_widget.get_roi()
            self.recognize_thread.set_frame(self._latest_frame.copy(), roi)

    def _cam_trigger_once(self):
        if self._latest_frame is None:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请先开启摄像头").exec()
            return
        if not self._ensure_vision():
            return
        # prevent double-click
        self.btn_cam_trigger.setEnabled(False)
        self.btn_cam_trigger.setText("识别中...")
        roi = self.camera_widget.get_roi()
        self.cam_w["status"].setText("状态: 手动识别中...")
        self.cam_w["status"].setStyleSheet("color: #00aaff; font-size: 13px;")
        self._cam_single_shot = SingleShotRecognizeThread(self.matcher, self._latest_frame.copy(), roi)
        self._cam_single_shot.result_ready.connect(self._on_cam_single_result)
        self._cam_single_shot.error_occurred.connect(self._on_cam_error)
        self._cam_single_shot.finished.connect(
            lambda: self._cleanup_single_shot("_cam_single_shot", self._cam_single_shot)
        )
        self._cam_single_shot.start()

    def _on_cam_single_result(self, result):
        if result:
            self._show_result(self.cam_w, result)
        else:
            self.cam_w["status"].setText("状态: 识别失败")
            self.cam_w["status"].setStyleSheet("color: #ff4444; font-size: 13px;")

    def _on_cam_error(self, err: str):
        self.cam_w["status"].setText(f"状态: 错误 - {err[:50]}")
        self.cam_w["status"].setStyleSheet("color: #ff4444; font-size: 13px;")

    # ────────────────────── Screen Capture ──────────────────────

    def _scr_select_area(self):
        # Toggle: cancel existing selection if active
        if self._scr_selector:
            self._cancel_scr_selection()
            return
        self._scr_selector = ScreenSelector()
        self._scr_selector.area_confirmed.connect(self._on_scr_area_confirmed)
        self._scr_selector.show()

    def _cancel_scr_selection(self):
        """Close selector, stop capture, and clear screen selection state."""
        if self._is_screen_capturing:
            self._stop_scr_recognize()
        if self._scr_selector:
            self._scr_selector.close()
            self._scr_selector = None
        self._scr_rect = None
        self.lbl_scr_status.setText("未选择区域")
        self.lbl_scr_status.setStyleSheet("color:#888888;font-size:12px;")

    def _on_scr_area_confirmed(self, rect):
        self._scr_rect = rect
        self._scr_capture.set_rect(rect)
        self.lbl_scr_status.setText(f"已选: {rect.width()}x{rect.height()}")
        self.lbl_scr_status.setStyleSheet("color:#00ff88;font-size:12px;")

    def _toggle_screen_capture(self):
        if self._is_screen_capturing:
            self._stop_scr_recognize()
            return

        if self._scr_rect is None:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请先框选屏幕区域").exec()
            return
        if not self._ensure_vision():
            return

        # start background recognize thread
        self._scr_recognize_thread = ScreenRecognizeThread(self.matcher)
        self._scr_recognize_thread.result_ready.connect(lambda r: self._show_result(self.scr_w, r))
        self._scr_recognize_thread.error_occurred.connect(self._on_scr_error)
        self._scr_recognize_thread.start()

        # start screen capture (runs in its own QThread)
        interval = int(self.spin_interval.value() * 1000)
        threshold = self.spin_change.value()
        self._scr_capture.frame_captured.connect(self._on_scr_frame)
        self._scr_capture.start_capture(interval, threshold)
        self._is_screen_capturing = True
        self.btn_scr_start.setText("停止实时识别 (Ctrl+Alt+D)")
        self.btn_scr_start.setStyleSheet("QPushButton{font-size:14px;font-weight:bold;background-color:#ff4444;color:white;}")
        self.scr_w["status"].setText("状态: 实时识别中...")
        self.scr_w["status"].setStyleSheet("color: #00aaff; font-size: 13px;")

    def _stop_scr_recognize(self):
        self._scr_capture.stop_capture()
        try:
            self._scr_capture.frame_captured.disconnect(self._on_scr_frame)
        except TypeError:
            pass
        if self._scr_recognize_thread:
            self._scr_recognize_thread._running = False
            self._scr_recognize_thread = None
        self._is_screen_capturing = False
        self.btn_scr_start.setText("开始实时识别 (Ctrl+Alt+D)")
        self.btn_scr_start.setStyleSheet("QPushButton{font-size:14px;font-weight:bold;}")
        self.scr_w["status"].setText("状态: 已停止")
        self.scr_w["status"].setStyleSheet("color: #ffaa00; font-size: 13px;")

    def _scr_capture_once(self):
        if self._scr_rect is None:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请先框选屏幕区域").exec()
            return
        if not self._ensure_vision():
            return
        # grab on main thread (fast, just a screenshot)
        frame = self._scr_capture.grab()
        if frame is None or frame.size == 0:
            self.statusBar().showMessage("截图失败", 3000)
            return
        # prevent double-click
        self.btn_scr_trigger.setEnabled(False)
        self.btn_scr_trigger.setText("识别中...")
        self.scr_w["status"].setText("状态: 识别中...")
        self.scr_w["status"].setStyleSheet("color: #00aaff; font-size: 13px;")
        self._scr_single_shot = SingleShotRecognizeThread(self.matcher, frame)
        self._scr_single_shot.result_ready.connect(self._on_scr_single_result)
        self._scr_single_shot.error_occurred.connect(self._on_scr_error)
        self._scr_single_shot.finished.connect(
            lambda: self._cleanup_single_shot("_scr_single_shot", self._scr_single_shot)
        )
        self._scr_single_shot.start()

    def _on_scr_single_result(self, result):
        if result:
            self._show_result(self.scr_w, result)
        else:
            self.scr_w["status"].setText("状态: 识别失败")
            self.scr_w["status"].setStyleSheet("color: #ff4444; font-size: 13px;")

    def _on_scr_frame(self, frame: np.ndarray):
        if self._scr_recognize_thread:
            self._scr_recognize_thread.set_frame(frame)

    def _on_scr_error(self, err: str):
        self.scr_w["status"].setText(f"状态: 错误 - {err[:50]}")
        self.scr_w["status"].setStyleSheet("color: #ff4444; font-size: 13px;")

    # ────────────────────── Quiz Bank ──────────────────────

    def _import_quiz(self):
        paths, _ = QFileDialog.getOpenFileNames(self, "选择题库文件（可多选）", "", "题库文件 (*.xlsx *.xls *.csv *.docx)")
        if not paths:
            return
        self._import_paths = paths
        names = ", ".join(Path(p).name for p in paths)
        self.lbl_file.setText(names)
        self.lbl_file.setStyleSheet("color: #e0e0e0;")
        # Only show column selector for non-docx files
        xlsx_paths = [p for p in paths if Path(p).suffix.lower() in (".xlsx", ".xls", ".csv")]
        if xlsx_paths:
            try:
                cols = detect_columns(xlsx_paths[0])
                self.combo_question_col.clear()
                self.combo_answer_col.clear()
                self.combo_question_col.addItem("自动检测", "")
                self.combo_answer_col.addItem("自动检测", "")
                for col in cols:
                    self.combo_question_col.addItem(col, col)
                    self.combo_answer_col.addItem(col, col)
            except Exception as e:
                self._msg_box(QMessageBox.Icon.Warning, "提示", f"读取文件列失败: {e}").exec()

    def _do_import(self):
        paths = getattr(self, "_import_paths", [])
        if not paths:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请先选择文件").exec()
            return
        q_col = self.combo_question_col.currentData() or ""
        a_col = self.combo_answer_col.currentData() or ""
        total = 0
        errors = []
        for path in paths:
            try:
                count = self.quiz_bank.load_file(path, q_col, a_col)
                total += count
            except ModuleNotFoundError as e:
                if "docx" in str(e):
                    errors.append(f"{Path(path).name}: 请安装 python-docx 库 (pip install python-docx)")
                else:
                    errors.append(f"{Path(path).name}: {e}")
            except Exception as e:
                errors.append(f"{Path(path).name}: {e}")
        self.lbl_bank_count.setText(f"题目数: {self.quiz_bank.count}")
        if not errors and total > 0:
            self.quiz_bank.save_cache()
        if errors:
            self._msg_box(QMessageBox.Icon.Warning, "部分导入失败", "\n".join(errors)).exec()
        else:
            self.statusBar().showMessage(f"成功导入 {total} 道题目", 3000)

    def _clear_bank(self):
        self.quiz_bank.clear()
        self.lbl_bank_count.setText("题目数: 0")
        self.statusBar().showMessage("题库已清空", 3000)

    def _save_bank(self):
        if self.quiz_bank.count == 0:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "题库为空，无需保存").exec()
            return
        self.quiz_bank.save_cache()
        self.statusBar().showMessage(f"题库已保存（{self.quiz_bank.count} 道题）", 3000)

    def _load_bank_cache(self):
        if self.quiz_bank.load_cache():
            self.lbl_bank_count.setText(f"题目数: {self.quiz_bank.count}")
            self.statusBar().showMessage(f"已从缓存加载 {self.quiz_bank.count} 道题", 3000)

    # ────────────────────── API Test ──────────────────────

    def _test_api(self):
        base_url = self.edit_base_url.text().strip()
        api_key = self.edit_api_key.text().strip()
        model = self.edit_model.text().strip()
        if not base_url or not api_key or not model:
            self._msg_box(QMessageBox.Icon.Warning, "提示", "请填写完整的 API 配置").exec()
            return
        self.btn_test_api.setEnabled(False)
        self.btn_test_api.setText("测试中...")
        self._api_test_thread = ApiTestThread(base_url, api_key, model)
        self._api_test_thread.success.connect(self._on_api_test_success)
        self._api_test_thread.failed.connect(self._on_api_test_failed)
        self._api_test_thread.finished.connect(self._on_api_test_done)
        self._api_test_thread.start()

    def _on_api_test_success(self, msg: str):
        self._msg_box(QMessageBox.Icon.Information, "成功", msg).exec()

    def _on_api_test_failed(self, msg: str):
        self._msg_box(QMessageBox.Icon.Critical, "失败", msg).exec()

    def _on_api_test_done(self):
        self.btn_test_api.setEnabled(True)
        self.btn_test_api.setText("测试连接")
        self._api_test_thread = None

    # ────────────────────── Close ──────────────────────

    def closeEvent(self, event):
        self._stop_cam_recognize()
        if self._is_screen_capturing:
            self._stop_scr_recognize()
        self._scr_capture.stop_capture()
        self._frame_timer.stop()
        self._hotkey.stop()
        if self.camera_thread:
            self.camera_thread.stop()
        if self._scr_selector:
            self._scr_selector.close()
        event.accept()

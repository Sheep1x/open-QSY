import ctypes
import logging
import os
import subprocess
import sys
import time
import traceback

from PyQt6.QtWidgets import QApplication, QMessageBox

from core.config import get_config_dir

_log_dir = str(get_config_dir())
os.makedirs(_log_dir, exist_ok=True)
_log_file = os.path.join(_log_dir, "app.log")

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    datefmt="%H:%M:%S",
    handlers=[
        logging.FileHandler(_log_file, encoding="utf-8", mode="w"),
        logging.StreamHandler(sys.stdout),
    ],
)

MUTEX_NAME = "Global\\Open-QSY-SingleInstance"
_mutex_handle = None


def check_single_instance() -> bool:
    global _mutex_handle
    kernel32 = ctypes.windll.kernel32
    _mutex_handle = kernel32.CreateMutexW(None, True, MUTEX_NAME)
    if kernel32.GetLastError() == 183:  # ERROR_ALREADY_EXISTS
        return False
    return True


def kill_other_instances():
    """Kill other Open-QSY.exe processes, but NOT the current one."""
    current_pid = os.getpid()
    try:
        result = subprocess.run(
            ["tasklist", "/FI", "IMAGENAME eq Open-QSY.exe", "/FO", "CSV", "/NH"],
            capture_output=True, text=True, timeout=5,
        )
        for line in result.stdout.strip().splitlines():
            parts = line.split(",")
            if len(parts) >= 2:
                try:
                    pid = int(parts[1].strip('"'))
                    if pid != current_pid:
                        subprocess.run(
                            ["taskkill", "/F", "/PID", str(pid)],
                            capture_output=True, timeout=5,
                        )
                except ValueError:
                    pass
    except Exception:
        pass


def main():
    if not check_single_instance():
        logging.warning("Killing existing instance...")
        kill_other_instances()
        time.sleep(1)
        if not check_single_instance():
            app = QApplication(sys.argv)
            QMessageBox.critical(None, "提示", "题眼已经在运行中")
            sys.exit(1)

    app = QApplication(sys.argv)
    app.setApplicationName("题眼")
    # Tray-resident app: hiding the main window must not quit the process
    app.setQuitOnLastWindowClosed(False)

    try:
        from ui.main_window import MainWindow
        window = MainWindow()
        window.show()

        # Start hotkey polling after window is shown
        window._hotkey.start()

        ret = app.exec()
    except Exception:
        crash_log = os.path.join(os.path.dirname(sys.executable), "crash.log")
        with open(crash_log, "w", encoding="utf-8") as f:
            f.write(traceback.format_exc())
        raise

    global _mutex_handle
    if _mutex_handle:
        ctypes.windll.kernel32.CloseHandle(_mutex_handle)

    sys.exit(ret)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把题库文件变成「眼镜扫一下就能换库」的二维码。

用法（在本机任意目录执行均可）：
    python glasses/tools/serve_bank.py 题库合并.json
    python glasses/tools/serve_bank.py glasses/GlassEyes/app/src/main/assets/qbank.idx
    python glasses/tools/serve_bank.py --url https://example.com/qbank.json

前两种：本机起一个 HTTP 服务，把文件发布到局域网，生成二维码并打开。
第三种：只把给定地址做成二维码（文件已经放在公网可访问的地方时用）。

眼镜端操作：主界面长按 → 单击进入扫码页 → 对准屏幕上的二维码。
扫到后自动下载、编译、替换，全过程不需要电脑参与。
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import shutil
import socket
import socketserver
import sys
import tempfile
import threading

def _utf8_console() -> None:
    """Windows 控制台默认 GBK，中文会变乱码，切到 UTF-8。"""
    if not sys.platform.startswith("win"):
        return
    try:
        import ctypes

        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        ctypes.windll.kernel32.SetConsoleCP(65001)
    except Exception:
        pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(os.path.dirname(HERE))
SHOTS = os.path.join(PROJECT, "glasses", "shots")


def lan_ip() -> str:
    """猜本机在局域网里的地址；连不通外网时退回 127.0.0.1。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def make_qr(text: str, out_path: str, scale: int = 10, border: int = 4) -> str:
    """生成二维码 PNG。优先 OpenCV；没有就用纯 Python 兜底（只打印不画图）。"""
    try:
        import cv2
        import numpy as np
    except ImportError:
        return ""

    enc = cv2.QRCodeEncoder_create()
    qr = enc.encode(text)  # uint8, 0/255
    if qr is None or qr.size == 0:
        return ""

    # 加留白：扫码器需要四周至少 4 个模块的静区
    qr = np.pad(qr, border, constant_values=255)
    qr = np.kron(qr, np.ones((scale, scale), dtype=np.uint8))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    cv2.imwrite(out_path, qr)
    return out_path


def open_file(path: str) -> None:
    try:
        if sys.platform.startswith("win"):
            os.startfile(path)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            os.system(f'open "{path}"')
        else:
            os.system(f'xdg-open "{path}" >/dev/null 2>&1')
    except Exception:
        pass


class Handler(http.server.SimpleHTTPRequestHandler):
    """只发这一个文件，顺带禁掉缓存，免得眼镜拿到旧副本。"""

    def end_headers(self):
        self.send_header("Cache-Control", "no-store, max-age=0")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("  [请求] " + (fmt % args) + "\n")


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def serve(path: str, port: int) -> str:
    """把 path 复制到一个临时目录并起服务，返回对外 URL。

    对外名字故意改成纯 ASCII 的 qbank.json / qbank.idx：
    中文文件名要在 URL 里百分号编码，眼镜端的 HttpURLConnection 和
    扫码器都容易在这里出岔子，不如根本不出现中文。
    """
    ext = os.path.splitext(path)[1].lower()
    name = "qbank.idx" if ext == ".idx" else "qbank.json"
    tmp = tempfile.mkdtemp(prefix="qsy-bank-")
    shutil.copyfile(path, os.path.join(tmp, name))

    handler = functools.partial(Handler, directory=tmp)
    httpd = Server(("0.0.0.0", port), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return f"http://{lan_ip()}:{port}/{name}"


def main() -> int:
    _utf8_console()
    ap = argparse.ArgumentParser(description="把题库文件变成眼镜可扫的二维码")
    ap.add_argument("file", nargs="?", help="题库文件（.json 或 .idx）")
    ap.add_argument("--url", help="直接把已有地址做成二维码，不启动服务")
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--qr", default=os.path.join(SHOTS, "qr_bank.png"))
    ap.add_argument("--scale", type=int, default=10, help="每个模块放大几倍像素")
    ap.add_argument("--no-open", action="store_true", help="不要自动打开图片")
    args = ap.parse_args()

    if args.url:
        url = args.url
        source_note = "外部地址"
    else:
        if not args.file:
            ap.error("要么给一个题库文件，要么用 --url 指定地址")
        path = args.file
        if not os.path.isfile(path):
            print(f"找不到文件：{path}", file=sys.stderr)
            return 2
        size = os.path.getsize(path)
        if size < 8 * 1024:
            print(f"文件太小（{size} B），可能不是题库", file=sys.stderr)
            return 2
        url = serve(path, args.port)
        source_note = f"{os.path.basename(path)}（{size:,} B）"

    qr_path = make_qr(url, args.qr, scale=args.scale)
    print()
    print(f"  题库：{source_note}")
    print(f"  地址：{url}")
    if qr_path:
        print(f"  二维码：{qr_path}")
    else:
        print("  二维码：未生成（缺 opencv-python），请自行把上面的地址转成二维码")
        print("          例：python -m pip install opencv-python")
    print()
    print("  眼镜上：主界面 长按 → 单击，进入扫码页，对准这个二维码。")
    print("  扫到后会自动下载、编译、替换，全程不用管。")
    if not args.url:
        print()
        print("  ※ 眼镜和电脑要连同一个 WiFi。不在同一网络就用 --url 走公网地址。")
        print("  ※ 这个窗口要保持打开。按 Ctrl+C 结束。")
    print()

    if qr_path and not args.no_open:
        open_file(qr_path)

    if args.url:
        return 0
    try:
        while True:
            threading.Event().wait(1.0)
    except KeyboardInterrupt:
        print("\n已停止。")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

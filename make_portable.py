"""Build a portable zip from dist/Open-QSY.

Usage:
    python make_portable.py           # package existing dist/Open-QSY
    python make_portable.py --build   # run PyInstaller first, then package

Output: dist/题眼-portable-<version>.zip
The zip contains a portable.marker file — when it sits next to Open-QSY.exe,
config and quiz bank are stored in the app's own "data" folder instead of
the user's home directory, so settings travel with the USB drive.
"""
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).parent
DIST = ROOT / "dist"
APP_DIR = DIST / "Open-QSY"


def _read_version() -> str:
    for line in (ROOT / "setup.iss").read_text(encoding="utf-8").splitlines():
        if line.startswith("#define MyAppVersion"):
            return line.split('"')[1]
    return "dev"


def main():
    if "--build" in sys.argv:
        subprocess.check_call(
            [sys.executable, "-m", "PyInstaller", "open-qsy.spec", "--noconfirm"],
            cwd=ROOT,
        )
    if not (APP_DIR / "Open-QSY.exe").exists():
        sys.exit("dist\\Open-QSY 不存在，请先运行: python make_portable.py --build")

    version = _read_version()
    out_dir = DIST / f"题眼-portable-{version}"
    if out_dir.exists():
        try:
            shutil.rmtree(out_dir)
        except PermissionError as e:
            sys.exit(f"无法清理旧目录（文件被占用，请先退出正在运行的题眼）: {e}")
    shutil.copytree(APP_DIR, out_dir)
    (out_dir / "portable.marker").write_text("", encoding="utf-8")

    zip_path = DIST / f"题眼-portable-{version}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for p in sorted(out_dir.rglob("*")):
            zf.write(p, p.relative_to(DIST))
    print(f"便携版已生成: {zip_path}")


if __name__ == "__main__":
    main()

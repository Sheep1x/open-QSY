"""量眼镜的功耗：单核 CPU 占用 + thermal_zone0 温度。

用法：python glasses/tools/power_probe.py
每组跑 60 秒，中间 force-stop 让机器歇一下。

注意：
- pid 取不到是常见 race（force-stop 后 1 秒就 start），这里轮询最多 10 秒。
- Android 的 SC_CLK_TCK 固定 100，Windows 上没有 os.sysconf。
"""
import os
import subprocess
import time

PKG = "com.openqsy.glasses"
DEV = "1901092551802596"
HZ = 100


def sh(*args):
    return subprocess.run(
        ["adb", "-s", DEV, "shell", *args], capture_output=True, text=True, encoding="utf-8", errors="ignore"
    ).stdout.strip()


def cpu_ticks(pid):
    out = sh("cat", f"/proc/{pid}/stat")
    if not out:
        return None
    parts = out.split()
    return int(parts[13]) + int(parts[14])


def temp():
    out = sh("cat", "/sys/class/thermal/thermal_zone0/temp")
    try:
        return int(out) / 1000
    except ValueError:
        return None


def wait_pid(timeout=10):
    for _ in range(timeout * 2):
        p = sh("pidof", PKG)
        if p:
            return int(p.split()[0])
        time.sleep(0.5)
    return None


def run(label, extra=(), seconds=60):
    sh("am", "force-stop", PKG)
    time.sleep(1)
    sh("input", "keyevent", "KEYCODE_WAKEUP")
    subprocess.run(
        ["adb", "-s", DEV, "shell", "am", "start", "-n", f"{PKG}/.activities.main.MainActivity", *extra],
        capture_output=True,
    )
    pid = wait_pid()
    if not pid:
        print(f"{label}: 取不到 pid")
        return
    # CameraX 初始化会先跑几轮 retry（日志里是 Expected camera missing from device），
    # 那 6 秒 CPU 是满的，必须等它过去再开始采样。
    time.sleep(15)
    t0, temp0 = cpu_ticks(pid), temp()
    start = time.time()
    time.sleep(seconds)
    t1, temp1 = cpu_ticks(pid), temp()
    elapsed = time.time() - start
    pct = (t1 - t0) / HZ / elapsed * 100
    print(f"{label}: CPU {pct:.1f}% (单核口径)  温度 {temp0} -> {temp1} ℃")


if __name__ == "__main__":
    run("默认：拍完显示那一张")
    sh("am", "force-stop", PKG)
    time.sleep(60)  # 组间冷却，否则第二组的起点温度就是第一组的终点
    run("--es preview 1：实时预览", ["--es", "preview", "1"])
    sh("am", "force-stop", PKG)

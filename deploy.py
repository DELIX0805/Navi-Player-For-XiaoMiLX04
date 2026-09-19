"""装机 + 启动 + 截图，用于界面改动的现场验证。

用法:
  python deploy.py            安装当前 APK 并截图到 build/ui.png
  python deploy.py 名字.png    安装当前 APK 并截图到 build/<名字>.png
  python deploy.py --shot 名字 只截图（不重装）
"""
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))
from devpath import adb_path

ADB = adb_path()
PKG = "com.tongsir.naviplayer"
APK = os.path.join(ROOT, "build", "naviplayer.apk")


def sh(*args, timeout=90):
    p = subprocess.run([ADB, "shell"] + list(args), capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode("utf-8", "replace").strip()


def shot(name):
    path = os.path.join(ROOT, "build", name)
    with open(path, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=90)
    return path, os.path.getsize(path)


def main():
    args = sys.argv[1:]
    only_shot = "--shot" in args
    args = [a for a in args if a != "--shot"]
    name = args[0] if args else "ui.png"

    if not only_shot:
        r = subprocess.run([ADB, "install", "-r", APK], capture_output=True, timeout=300)
        print("install:", (r.stdout + r.stderr).decode("utf-8", "replace").strip())
        sh("am", "force-stop", PKG)
        time.sleep(1)
        sh("am", "start", "-n", PKG + "/.MainActivity")
        time.sleep(2.0)

    p, n = shot(name)
    print("shot -> %s (%d bytes)" % (p, n))


if __name__ == "__main__":
    main()

# -*- coding: utf-8 -*-
"""对话框视觉验证：装机后依次截取各状态，用于核对文字/背景对比度。

用法: python dlgshot.py [apk路径]
输出: 工程上级目录下的 dlg_*.png
"""
import os
import subprocess
import sys
import time

from devpath import adb_path

A = adb_path()
ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.dirname(ROOT)
PKG = "com.tongsir.naviplayer"


def sh(*a):
    r = subprocess.run([A] + list(a), capture_output=True, text=True)
    return ((r.stdout or "") + (r.stderr or "")).strip()


def shot(name):
    sh("shell", "screencap", "-p", "/sdcard/t.png")
    sh("pull", "/sdcard/t.png", os.path.join(OUT, name))
    print("shot", name)


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))


def longpress(x, y):
    sh("shell", "input", "swipe", str(x), str(y), str(x), str(y), "900")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "naviplayer.apk")
    print("install:", sh("install", "-r", apk))
    sh("shell", "am", "force-stop", PKG)
    time.sleep(1)
    sh("shell", "am", "start", "-n", PKG + "/.MainActivity")
    time.sleep(6)

    longpress(120, 34)          # 长按顶栏品牌 → 服务器配置对话框
    time.sleep(3)
    shot("dlg_setup.png")

    tap(400, 163)               # 点开输入框 → 键盘应正常弹出
    time.sleep(3)
    shot("dlg_ime.png")
    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)

    tap(78, 326)                # 取消勾选 → 未选态应为白色描边空框
    time.sleep(2)
    shot("dlg_cboff.png")

    tap(587, 391)               # 取消，关闭配置对话框
    time.sleep(2)

    longpress(600, 250)         # 长按歌词区 → 歌词时间微调
    time.sleep(3)
    shot("dlg_offset.png")

    sh("shell", "input", "keyevent", "KEYCODE_BACK")
    sh("shell", "rm", "/sdcard/t.png")
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())

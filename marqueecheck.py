"""跑马灯实测：定位歌名/歌手区域 → 验证"短文本静止 / 长文本滚动 + 停顿 + 位移速度"。

用法:
  python marqueecheck.py nodes            导出界面节点，打印歌名/歌手的精确边界与控件类名
  python marqueecheck.py locate [行]      只扫描指定行的文本 y 范围（默认 title）
  python marqueecheck.py still  [行]      验证当前（短）文本静止不动
  python marqueecheck.py seek             循环切歌直到找到超长歌名
  python marqueecheck.py track [帧数] [fast] [行]   单行位移轨迹
  python marqueecheck.py both  [帧数]     歌名 + 歌手两行同时取样，逐帧对比
  python marqueecheck.py gif   [帧数]     录两行区域的动图
  python marqueecheck.py stability        后台往返后滚动是否恢复

行参数: title | artist | both（默认 title）
"""
import os
import re
import subprocess
import sys
import time

from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
from devpath import adb_path

ADB = adb_path()
TMP = os.path.join(ROOT, "build", "_m.png")

# 文本行所在区域（800x480 屏，封面下方那两块）。
# 实测：歌名文字 y=269..292、歌手 y=308..323，均从 x=30 起；
# 右界被控件裁在 x=204（封面列 116dp x1.5 密度 = 174px，加 20dp 页边距 = 30px）。
# 所以检测框取 x0=26（避开左边缘那条装饰白竖条 x=12..17）、x1=206（不伸进歌词区）。
ROWS = {
    "title": (26, 264, 206, 298),
    "artist": (26, 302, 206, 330),
}
BOX = ROWS["title"]          # 兼容旧调用：单行命令默认作用于歌名


def sh(*a, timeout=90):
    p = subprocess.run([ADB, "shell"] + list(a), capture_output=True, timeout=timeout)
    return (p.stdout + p.stderr).decode("utf-8", "replace").strip()


def grab():
    """返回 (PIL图像, 截图完成时刻)"""
    with open(TMP, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=90)
    t = time.time()
    return Image.open(TMP).convert("RGB"), t


def box_of(row):
    return ROWS.get(row, ROWS["title"])


def mask(img, box):
    """区域内白像素掩码（行优先一维列表）"""
    x0, y0, x1, y1 = box
    px = img.load()
    w = x1 - x0
    return [1 if sum(px[x, y]) / 3.0 > 150 else 0
            for y in range(y0, y1) for x in range(x0, x1)], w, y1 - y0


def locate(img, row="title"):
    """扫描出该行文本的精确 y 范围"""
    x0, y0, x1, y1 = box_of(row)
    px = img.load()
    print("  行内白像素数（y:count）  [%s]" % row)
    for y in range(y0, y1):
        c = sum(1 for x in range(x0, x1) if sum(px[x, y]) / 3.0 > 150)
        if c:
            print("    y=%3d  %3d %s" % (y, c, "#" * min(60, c)))


def col_profile(m, w, h):
    """列投影（每列的白像素数）。用它做移位匹配比逐像素快 20 多倍，对文本足够稳。"""
    return [sum(m[y * w + x] for y in range(h)) for x in range(w)]


def shift_between(m1, m2, w, h, maxdx=150):
    """估算 m2 相对 m1 的水平位移（文本左移 -> 正值）。

    先把两帧压成列投影，再对每个候选位移取 L1 距离，最小者即位移量。
    """
    a = col_profile(m1, w, h)
    b = col_profile(m2, w, h)
    if not any(a) or not any(b):
        return 0, 0
    best_dx, best_err = 0, None
    for dx in range(0, maxdx + 1):
        n = w - dx
        if n <= 10:
            break
        err = 0
        for x in range(n):
            err += abs(a[x + dx] - b[x])
        if best_err is None or err < best_err:
            best_err, best_dx = err, dx
    return best_dx, best_err


def diff_count(m1, m2):
    return sum(1 for a, b in zip(m1, m2) if a != b)


def cmd_nodes():
    """导出界面节点：直接看 @id/title 与 @id/artist 的类名、边界"""
    sh("uiautomator", "dump", "/sdcard/_nodes.xml")
    xml = sh("cat", "/sdcard/_nodes.xml")
    if not xml.strip():
        print("uiautomator dump 为空（界面可能被浮层遮挡）")
        return 1
    print("%-10s %-34s %-22s %s" % ("id", "class", "bounds", "text"))
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        rid = re.search(r'resource-id="([^"]*)"', tag)
        cls = re.search(r'class="([^"]*)"', tag)
        bnd = re.search(r'bounds="([^"]*)"', tag)
        txt = re.search(r'text="([^"]*)"', tag)
        if not rid:
            continue
        name = rid.group(1).split("/")[-1]
        if name not in ("title", "artist", "lyric", "cover"):
            continue
        print("%-10s %-34s %-22s %s"
              % (name, cls.group(1).split(".")[-1] if cls else "?",
                 bnd.group(1) if bnd else "?", (txt.group(1) if txt else "")[:30]))
    return 0


def cmd_both(n, fast):
    """两行同时取样：一次截图测两行，直接看是否同步滚动"""
    prev = {}
    prev_t = None
    t0 = None
    print("帧  时刻s | 歌名:白px  位移  差异  速度px/s | 歌手:白px  位移  差异  速度px/s")
    for i in range(n):
        img, t = grab()
        if t0 is None:
            t0 = t
        cur = {r: mask(img, box_of(r)) for r in ("title", "artist")}
        cells = []
        for r in ("title", "artist"):
            m, w, h = cur[r]
            if prev_t is None:
                cells.append("%4d     -      -        -" % sum(m))
            else:
                dx, _ = shift_between(prev[r][0], m, w, h)
                dt = t - prev_t
                d = diff_count(prev[r][0], m)
                v = (dx / dt) if dt > 0 else 0
                cells.append("%4d   %4d   %5d    %6.1f" % (sum(m), dx, d, v))
        print("%3d  %6.2f | %s | %s" % (i, t - t0, cells[0], cells[1]))
        prev = cur
        prev_t = t
        if not fast:
            time.sleep(0.35)
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "locate"

    if cmd == "nodes":
        return cmd_nodes()

    if cmd == "locate":
        row = sys.argv[2] if len(sys.argv) > 2 else "title"
        img, _ = grab()
        img.save(os.path.join(ROOT, "build", "locate.png"))
        locate(img, row)
        return 0

    if cmd == "still":
        row = sys.argv[2] if len(sys.argv) > 2 else "title"
        box = box_of(row)
        frames = []
        for _ in range(5):
            img, t = grab()
            frames.append((mask(img, box)[0], t))
            time.sleep(0.45)
        print("帧间差异白像素数（应为 0 = 完全静止）  [%s]:" % row)
        ok = True
        for i in range(1, len(frames)):
            d = diff_count(frames[i - 1][0], frames[i][0])
            ok = ok and d == 0
            print("  帧%d->%d  diff=%d  字数=%d" % (i - 1, i, d, sum(frames[i][0])))
        print("结果:", "静止（未超宽不滚动）✓" if ok else "在动 ✗")
        return 0

    if cmd == "seek":
        row = sys.argv[2] if len(sys.argv) > 2 else "title"
        n_rounds = int(sys.argv[3]) if len(sys.argv) > 3 else 25
        for i in range(n_rounds):
            sh("input", "tap", "571", "428")   # 下一曲
            time.sleep(4)                      # 等这首稳定下来（换歌瞬间文本会替换，差异不是滚动）
            # 取 6 帧。两个判据同时成立才算命中：
            #   1) 多数帧对之间有差异；2) 可见文本的列投影出现 >=3 种形态。
            # 换歌造成的"整条文本被替换"只会产生 2 种形态（替换前 / 替换后），据此排除误报。
            ms = []
            spans = []
            for k in range(6):
                img, _ = grab()
                m, w, h = mask(img, box_of(row))
                ms.append(m)
                spans.append(tuple(col_profile(m, w, h)))
                if k < 5:
                    time.sleep(0.6)
            ds = [diff_count(ms[k], ms[k + 1]) for k in range(5)]
            moving = sum(1 for d in ds if d > 0)
            kinds = len(set(spans))
            ok = moving >= 4 and kinds >= 3
            print("第%2d首: [%s] 动/总帧对=%d/5  列投影形态=%d种  → %s"
                  % (i, row, moving, kinds, "持续滚动!" if ok else "(静止或仅换歌)"))
            if ok:
                img.save(os.path.join(ROOT, "build", "seek_hit.png"))
                print("已命中，截图存 build/seek_hit.png")
                return 0
        print("%d 首都没找到超长的 %s" % (n_rounds, row))
        return 1

    if cmd == "track":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 26
        fast = "fast" in sys.argv
        row = "title"
        for a in sys.argv[2:]:
            if a in ROWS:
                row = a
        box = box_of(row)
        prev = None
        prev_t = None
        t0 = None
        print("帧  时刻s   白像素  位移px  差异px   速度px/s  状态   [%s]" % row)
        for i in range(n):
            img, t = grab()
            m, w, h = mask(img, box)
            if t0 is None:
                t0 = t
            if prev is None:
                print("%3d  %6.2f  %4d      -        -         -    -" % (i, 0.0, sum(m)))
            else:
                dx, _ = shift_between(prev, m, w, h)
                dt = t - prev_t
                d = diff_count(prev, m)
                v = (dx / dt) if dt > 0 else 0
                st = "停顿" if d == 0 else ("滚动 %.1f" % v)
                print("%3d  %6.2f  %4d   %4d     %5d    %7.1f   %s"
                      % (i, t - t0, sum(m), dx, d, v, st))
            prev, prev_t = m, t
            if not fast:
                time.sleep(0.35)
        return 0

    if cmd == "both":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 24
        return cmd_both(n, "fast" in sys.argv)

    if cmd == "gif":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else 10
        box = (26, 262, 210, 330)   # 歌名 + 歌手两行（右侧到控件裁切边界为止）
        cw, ch = box[2] - box[0], box[3] - box[1]
        frames = []
        for i in range(n):
            img, _ = grab()
            frames.append(img.crop(box).resize((cw * 2, ch * 2), Image.LANCZOS))
            print("  帧", i + 1, "/", n)
            if i < n - 1:
                time.sleep(0.35)
        out = os.path.join(ROOT, "marquee.gif")
        # 实拍间隔约 1.1s/帧，这里用 350ms 播放（约 3 倍速），否则看不出节奏
        frames[0].save(out, save_all=True, append_images=frames[1:],
                       duration=350, loop=0, optimize=True)
        print("gif -> %s (%.1f KB, %d 帧)" % (out, os.path.getsize(out) / 1024.0, n))
        return 0

    if cmd == "stability":
        pkg = "com.tongsir.naviplayer"
        sh("logcat", "-c")
        sh("input", "keyevent", "KEYCODE_HOME")
        time.sleep(4)
        sh("am", "start", "-n", pkg + "/.MainActivity")
        time.sleep(3)
        ms = []
        for k in range(3):
            img, _ = grab()
            ms.append(mask(img, box_of("title"))[0])
            if k < 2:
                time.sleep(0.8)
        d = max(diff_count(ms[0], ms[1]), diff_count(ms[1], ms[2]))
        print("回前台后帧间差异 = %d  → %s" % (d, "滚动已恢复 ✓" if d > 0 else "未恢复 ✗"))
        print("--- crash buffer ---")
        print(sh("logcat", "-d", "-b", "crash", "-t", "20") or "(空)")
        print("--- Navi log ---")
        print(sh("logcat", "-d", "-t", "10", "-s", "Navi:*") or "(空)")
        return 0

    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())

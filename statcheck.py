"""验证底部状态条：连续截图比对，检查延迟是否每秒刷新。

用法：python statcheck.py [帧数=4] [间隔秒=1.2]
前提：设备已连 adb，应用已装机并处于前台。

输出：build/_srvseq.png（各帧的控制行纵向拼接，放大 2 倍，便于目视）
      以及每帧的信息区/状态区白像素统计与帧间差异像素数。
判据：srv_diff 每帧都 >0（延迟在刷新）、meta_diff 全为 0（歌曲信息不乱跳）。

区域常量 MET/SRV 是按 800x480 实测的控制行坐标写死的，
换分辨率需要按新的控制行 y 范围重新标定。
"""
import subprocess, sys, os, zlib, struct, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pxcheck import load_png, px

from devpath import adb_path

ADB = adb_path()
HERE = os.path.dirname(os.path.abspath(__file__))
N = int(sys.argv[1]) if len(sys.argv) > 1 else 4
GAP = float(sys.argv[2]) if len(sys.argv) > 2 else 1.2


def save_png(path, w, h, rgb):
    raw = bytearray()
    stride = w * 3
    for y in range(h):
        raw.append(0)
        raw += rgb[y * stride:(y + 1) * stride]

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    ihdr = struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) + chunk(b'IDAT', zlib.compress(bytes(raw), 6)) + chunk(b'IEND', b'')
    open(path, 'wb').write(data)


def crop(buf, w, bpp, x0, y0, x1, y1, zoom=1):
    cw, chh = (x1 - x0) * zoom, (y1 - y0) * zoom
    out = bytearray(cw * chh * 3)
    for y in range(chh):
        sy = y0 + y // zoom
        for x in range(cw):
            sx = x0 + x // zoom
            r, g, b = px(buf, w, bpp, sx, sy)
            i = (y * cw + x) * 3
            out[i] = r; out[i + 1] = g; out[i + 2] = b
    return cw, chh, out


frames = []
for i in range(N):
    p = os.path.join(HERE, "build", "_p%d.png" % i)
    with open(p, "wb") as f:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=f, timeout=60)
    frames.append(p)
    if i < N - 1:
        time.sleep(GAP)

MET = (20, 396, 320, 464)   # 左：格式 + 码率
SRV = (596, 396, 790, 464)  # 右：状态灯 + 延迟

def whites(w, h, bpp, buf, box):
    x0, y0, x1, y1 = box
    n = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            r, g, b = px(buf, w, bpp, x, y)
            if r > 180 and g > 180 and b > 180:
                n += 1
    return n

bufs = []
for p in frames:
    w, h, bpp, buf = load_png(p)
    bufs.append((w, h, bpp, buf))

print("== 每帧白像素数（文字/亮点越多=内容越丰富）==")
for i, (w, h, bpp, buf) in enumerate(bufs):
    print("frame%d  meta=%d  srv=%d" % (i, whites(w, h, bpp, buf, MET), whites(w, h, bpp, buf, SRV)))

print("== 帧间差异像素数（>24 级灰度差算变化）==")
for i in range(1, len(bufs)):
    w, h, bpp, a = bufs[0]
    _, _, _, b = bufs[i]
    dm = ds = 0
    for (x0, y0, x1, y1), acc in ((MET, 'm'), (SRV, 's')):
        d = 0
        for y in range(y0, y1):
            for x in range(x0, x1):
                ra, ga, ba = px(a, w, bpp, x, y)
                rb, gb, bb = px(b, w, bpp, x, y)
                if abs(ra - rb) + abs(ga - gb) + abs(ba - bb) > 72:
                    d += 1
        if acc == 'm':
            dm = d
        else:
            ds = d
    print("frame0 vs frame%d: meta_diff=%d  srv_diff=%d" % (i, dm, ds))

# 拼接：每帧的 srv 区域 + meta 区域
parts = []
for (w, h, bpp, buf) in bufs:
    cw1, ch1, c1 = crop(buf, w, bpp, MET[0], MET[1], MET[2], MET[3], 2)
    cw2, ch2, c2 = crop(buf, w, bpp, SRV[0], SRV[1], SRV[2], SRV[3], 2)
    parts.append((cw1, ch1, c1, cw2, ch2, c2))

CW = max(p[0] + p[3] for p in parts)
CH = sum(max(p[1], p[4]) for p in parts) + 6 * (len(parts) - 1)
canvas = bytearray(b'\x20\x20\x20' * (CW * CH))
yoff = 0
for (cw1, ch1, c1, cw2, ch2, c2) in parts:
    rowh = max(ch1, ch2)
    for y in range(ch1):
        base = ((yoff + y) * CW) * 3
        canvas[base:base + cw1 * 3] = c1[y * cw1 * 3:(y + 1) * cw1 * 3]
    xoff = CW - cw2
    for y in range(ch2):
        base = ((yoff + y) * CW + xoff) * 3
        canvas[base:base + cw2 * 3] = c2[y * cw2 * 3:(y + 1) * cw2 * 3]
    yoff += rowh + 6

out = os.path.join(HERE, "build", "_srvseq.png")
save_png(out, CW, CH, canvas)
print("saved", out, CW, "x", CH)

# -*- coding: utf-8 -*-
"""应用图标生成器（零依赖）。

同一套矢量定义（归一化 0..1 坐标）同时输出：
  - SVG 预览（用于选型确认，所见即所得）
  - 多密度 PNG（直接落进 res/drawable-*/ic_launcher.png）

用法:
  python mkicon.py preview          生成 4 个候选的 PNG + SVG 预览
  python mkicon.py apply 1          把候选 1 写入工程资源目录
  python mkicon.py sheet            生成 2x2 预览图（供内联展示）
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "res")
OUT = os.path.join(ROOT, "icons")   # 不放 build/，避免被 build.py 清掉

# ---------------------------------------------------------------- 调色
INK = "#0E1014"      # 与 App 主背景一致
BLUE = "#1B2A6B"     # 深蓝
WHITE = "#FFFFFF"


# ---------------------------------------------------------------- 形状
def rrect(cx, cy, w, h, r, c):
    return ("rrect", (cx, cy, w, h, r), c)


def circle(cx, cy, r, c):
    return ("circle", (cx, cy, r), c)


def ring(cx, cy, ro, ri, c):
    return ("ring", (cx, cy, ro, ri), c)


def line(x1, y1, x2, y2, w, c):
    return ("line", (x1, y1, x2, y2, w), c)


def ellipse(cx, cy, rx, ry, deg, c):
    return ("ellipse", (cx, cy, rx, ry, deg), c)


def inside(sh, x, y):
    t, p = sh[0], sh[1]
    if t == "rrect":
        cx, cy, w, h, r = p
        dx = abs(x - cx)
        dy = abs(y - cy)
        qx = max(dx - (w / 2.0 - r), 0.0)
        qy = max(dy - (h / 2.0 - r), 0.0)
        return math.hypot(qx, qy) <= r and dx <= w / 2.0 and dy <= h / 2.0
    if t == "circle":
        cx, cy, r = p
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    if t == "ring":
        cx, cy, ro, ri = p
        d2 = (x - cx) ** 2 + (y - cy) ** 2
        return ri * ri <= d2 <= ro * ro
    if t == "line":
        x1, y1, x2, y2, w = p
        dx, dy = x2 - x1, y2 - y1
        ln = dx * dx + dy * dy
        if ln <= 1e-12:
            return (x - x1) ** 2 + (y - y1) ** 2 <= (w / 2.0) ** 2
        u = ((x - x1) * dx + (y - y1) * dy) / ln
        u = 0.0 if u < 0 else (1.0 if u > 1 else u)
        px, py = x1 + u * dx, y1 + u * dy
        return (x - px) ** 2 + (y - py) ** 2 <= (w / 2.0) ** 2
    if t == "ellipse":
        cx, cy, rx, ry, deg = p
        a = math.radians(deg)
        ca, sa = math.cos(a), math.sin(a)
        dx, dy = x - cx, y - cy
        u = dx * ca + dy * sa
        v = -dx * sa + dy * ca
        return (u / rx) ** 2 + (v / ry) ** 2 <= 1.0
    return False


def to_rgb(hx):
    hx = hx.lstrip("#")
    return (int(hx[0:2], 16), int(hx[2:4], 16), int(hx[4:6], 16))


# ---------------------------------------------------------------- 图形定义
def bbox(shapes):
    """形状集合的包围盒（解析计算，不需采样）。"""
    x0 = y0 = 1e9
    x1 = y1 = -1e9
    for t, p, _c in shapes:
        if t == "rrect":
            cx, cy, w, h, _r = p
            a, b = cx - w / 2.0, cy - h / 2.0
            c, d = cx + w / 2.0, cy + h / 2.0
        elif t == "circle":
            cx, cy, r = p
            a, b, c, d = cx - r, cy - r, cx + r, cy + r
        elif t == "ring":
            cx, cy, ro, _ri = p
            a, b, c, d = cx - ro, cy - ro, cx + ro, cy + ro
        elif t == "line":
            x1_, y1_, x2_, y2_, w = p
            a, b = min(x1_, x2_) - w / 2.0, min(y1_, y2_) - w / 2.0
            c, d = max(x1_, x2_) + w / 2.0, max(y1_, y2_) + w / 2.0
        else:
            cx, cy, rx, ry, deg = p
            ar = math.radians(deg)
            ex = math.hypot(rx * math.cos(ar), ry * math.sin(ar))
            ey = math.hypot(rx * math.sin(ar), ry * math.cos(ar))
            a, b, c, d = cx - ex, cy - ey, cx + ex, cy + ey
        x0, y0 = min(x0, a), min(y0, b)
        x1, y1 = max(x1, c), max(y1, d)
    return x0, y0, x1, y1


def _is_size(t, i):
    """该参数是「尺寸/线宽」而非位置坐标。"""
    if t == "line":
        return i == 4
    if t == "ellipse":
        return i in (2, 3)
    return i >= 2


def xform(shapes, k, px=0.5, py=0.5):
    """以 (px,py) 为基点等比缩放：位置参数平移缩放，尺寸参数只缩放。"""
    out = []
    for t, p, c in shapes:
        q = []
        for i, v in enumerate(p):
            if _is_size(t, i):
                q.append(v * k)
            elif t == "ellipse" and i == 4:
                q.append(v)  # 旋转角
            else:
                c0 = px if i % 2 == 0 else py
                q.append(c0 + (v - c0) * k)
        out.append((t, tuple(q), c))
    return out


def move(shapes, dx, dy):
    """整体平移（只动位置参数）。"""
    out = []
    for t, p, c in shapes:
        q = []
        for i, v in enumerate(p):
            if _is_size(t, i) or (t == "ellipse" and i == 4):
                q.append(v)
            else:
                q.append(v + (dx if i % 2 == 0 else dy))
        out.append((t, tuple(q), c))
    return out


def fit(shapes, target):
    """缩放 + 平移，使包围盒居中且最大边长为 target。"""
    x0, y0, x1, y1 = bbox(shapes)
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    k = target / max(x1 - x0, y1 - y0)
    return move(xform(shapes, k, cx, cy), 0.5 - cx, 0.5 - cy)


def zoom(shapes, k):
    """围绕画布中心缩放全部形状（含线宽与半径）。"""
    return xform(shapes, k, 0.5, 0.5)


def glyph_note(color):
    """双八分音符（带横梁）。x 已整体左移 0.008 使视觉居中。"""
    return [
        ellipse(0.327, 0.700, 0.116, 0.086, -18, color),
        ellipse(0.647, 0.700, 0.116, 0.086, -18, color),
        line(0.438, 0.700, 0.438, 0.310, 0.056, color),
        line(0.758, 0.700, 0.758, 0.268, 0.056, color),
        line(0.466, 0.320, 0.730, 0.281, 0.082, color),
    ]


def glyph_record(color):
    """唱片：外环 + 中心孔。"""
    return [
        ring(0.5, 0.5, 0.395, 0.347, color),
        circle(0.5, 0.5, 0.088, color),
    ]


def glyph_single_note(color):
    """单八分音符（顶栏小标记用，去掉横梁以免小尺寸糊成一团）。"""
    return [
        ellipse(0.365, 0.720, 0.135, 0.100, -18, color),
        line(0.488, 0.720, 0.488, 0.250, 0.062, color),
    ]


def glyph_lyric_note(color):
    """歌词三行 + 单音符（呼应“歌词优先”的产品定位）。"""
    return [
        rrect(0.350, 0.375, 0.320, 0.060, 0.030, color),
        rrect(0.335, 0.500, 0.290, 0.060, 0.030, color),
        rrect(0.310, 0.625, 0.240, 0.060, 0.030, color),
        ellipse(0.700, 0.628, 0.078, 0.058, -18, color),
        line(0.778, 0.628, 0.778, 0.335, 0.046, color),
    ]


def design(tile, glyph_color, glyph, k=1.0):
    return [rrect(0.5, 0.5, 0.94, 0.94, 0.235, tile)] + zoom(glyph(glyph_color), k)


DESIGNS = [
    ("note_dark", "深色底 · 音符", design(INK, WHITE, glyph_note, 1.05)),
    ("note_blue", "深蓝底 · 音符", design(BLUE, WHITE, glyph_note, 1.05)),
    ("record_dark", "深色底 · 唱片", design(INK, WHITE, glyph_record)),
    ("note_white", "白底 · 深色音符", design(WHITE, INK, glyph_note, 1.05)),
]


# ---------------------------------------------------------------- SVG
def svg_shapes(shapes):
    out = []
    for t, p, c in shapes:
        if t == "rrect":
            cx, cy, w, h, r = p
            out.append('<rect x="%.4f" y="%.4f" width="%.4f" height="%.4f" rx="%.4f" fill="%s"/>'
                       % (cx - w / 2, cy - h / 2, w, h, r, c))
        elif t == "circle":
            cx, cy, r = p
            out.append('<circle cx="%.4f" cy="%.4f" r="%.4f" fill="%s"/>' % (cx, cy, r, c))
        elif t == "ring":
            cx, cy, ro, ri = p
            out.append('<circle cx="%.4f" cy="%.4f" r="%.4f" fill="none" stroke="%s" stroke-width="%.4f"/>'
                       % (cx, cy, (ro + ri) / 2.0, c, ro - ri))
        elif t == "line":
            x1, y1, x2, y2, w = p
            out.append('<line x1="%.4f" y1="%.4f" x2="%.4f" y2="%.4f" stroke="%s" stroke-width="%.4f" stroke-linecap="round"/>'
                       % (x1, y1, x2, y2, c, w))
        elif t == "ellipse":
            cx, cy, rx, ry, deg = p
            out.append('<ellipse cx="%.4f" cy="%.4f" rx="%.4f" ry="%.4f" fill="%s" transform="rotate(%.2f %.4f %.4f)"/>'
                       % (cx, cy, rx, ry, c, deg, cx, cy))
    return "".join(out)


def icon_svg(shapes, px):
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 1 1">%s</svg>'
            % (px, px, svg_shapes(shapes)))


# ---------------------------------------------------------------- PNG
def png_bytes(w, h, pix):
    """pix: bytearray RGBA"""
    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)
        raw += pix[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def raster(shapes, size, ss=5):
    """超采样抗锯齿。返回 RGBA bytearray。"""
    cols = [to_rgb(c) for _, _, c in shapes]
    pix = bytearray(size * size * 4)
    total = ss * ss
    for py in range(size):
        for px in range(size):
            ar = ag = ab = 0
            hit = 0
            for sy in range(ss):
                y = (py + (sy + 0.5) / ss) / size
                for sx in range(ss):
                    x = (px + (sx + 0.5) / ss) / size
                    ci = -1
                    for i, sh in enumerate(shapes):
                        if inside(sh, x, y):
                            ci = i
                    if ci >= 0:
                        r, g, b = cols[ci]
                        ar += r
                        ag += g
                        ab += b
                        hit += 1
            o = (py * size + px) * 4
            if hit:
                pix[o] = ar // hit
                pix[o + 1] = ag // hit
                pix[o + 2] = ab // hit
                pix[o + 3] = (hit * 255) // total
            else:
                pix[o + 3] = 0
    return pix


def write(path, data):
    d = os.path.dirname(path)
    if d and not os.path.exists(d):
        os.makedirs(d)
    with open(path, "wb") as f:
        f.write(data)


# ---------------------------------------------------------------- 动作
DENS = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]


def do_preview():
    for i, (name, _label, shapes) in enumerate(DESIGNS, 1):
        for px in (256, 132, 48):
            write(os.path.join(OUT, "%d_%s_%d.png" % (i, name, px)),
                  png_bytes(px, px, raster(shapes, px)))
        write(os.path.join(OUT, "%d_%s.svg" % (i, name)), icon_svg(shapes, 256).encode("utf-8"))
    print("preview ->", OUT)
    return 0


def do_apply(idx):
    name, label, shapes = DESIGNS[idx - 1]
    for dens, px in DENS:
        write(os.path.join(RES, "drawable-" + dens, "ic_launcher.png"),
              png_bytes(px, px, raster(shapes, px)))
    legacy = os.path.join(RES, "drawable", "ic_launcher.png")
    if os.path.exists(legacy):
        os.remove(legacy)
        print("removed legacy", legacy)
    print("applied #%d %s (%s)" % (idx, name, label))
    return 0


def do_sheet():
    """2x2/单行候选对照图（SVG，用于内联展示，所见即所得）。"""
    box, gap, top, small = 132, 24, 34, 48
    xs = [40 + i * (box + gap) for i in range(4)]
    parts = []
    for x, (name, label, shapes) in zip(xs, DESIGNS):
        parts.append('<rect x="%d" y="%d" width="%d" height="%d" rx="16" fill="none" '
                     'stroke="currentColor" stroke-opacity="0.18"/>' % (x, top, box, box))
        parts.append('<svg x="%d" y="%d" width="%d" height="%d" viewBox="0 0 1 1">%s</svg>'
                     % (x, top, box, box, svg_shapes(shapes)))
        parts.append('<text x="%.1f" y="%d" text-anchor="middle" font-size="13" '
                     'font-family="system-ui, sans-serif" fill="currentColor">%s</text>'
                     % (x + box / 2.0, top + box + 26, label))
        parts.append('<svg x="%.1f" y="%d" width="%d" height="%d" viewBox="0 0 1 1">%s</svg>'
                     % (x + (box - small) / 2.0, top + box + 44, small, small, svg_shapes(shapes)))
    h = top + box + 44 + small + 30
    parts.append('<text x="340" y="%d" text-anchor="middle" font-size="12" '
                 'font-family="system-ui, sans-serif" fill="currentColor" '
                 'fill-opacity="0.6">下排为桌面上的实际显示大小</text>' % (h - 12))
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 680 %d" width="100%%" '
           'role="img"><title>应用图标候选方案</title>'
           '<desc>四个候选图标：深色底音符、深蓝底音符、深色底唱片、白底深色音符</desc>%s</svg>'
           % (h, "".join(parts)))
    path = os.path.join(OUT, "sheet.svg")
    write(path, svg.encode("utf-8"))
    with open(os.path.join(OUT, "sheet.txt"), "w", encoding="utf-8") as f:
        f.write(svg)
    print("sheet ->", path)
    return 0


def do_logo(glyph=None):
    """顶栏小标记：透明底白色单音符，按密度出图（16dp）。"""
    shapes = fit((glyph or glyph_single_note)("#FFFFFF"), 0.86)
    x0, y0, x1, y1 = bbox(shapes)
    print("logo bbox center=(%.4f,%.4f)" % ((x0 + x1) / 2.0, (y0 + y1) / 2.0))
    for dens, px in (("mdpi", 16), ("hdpi", 24), ("xhdpi", 32), ("xxhdpi", 48)):
        write(os.path.join(RES, "drawable-" + dens, "ic_logo.png"),
              png_bytes(px, px, raster(shapes, px)))
    # 深色底衬图，便于人眼核对（不进 APK）
    write(os.path.join(OUT, "logo_on_dark.png"),
          png_bytes(192, 192, raster([rrect(0.5, 0.5, 1.0, 1.0, 0.0, INK)] + shapes, 192)))
    print("logo written")
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "preview"
    if cmd == "apply":
        sys.exit(do_apply(int(sys.argv[2])))
    if cmd == "sheet":
        sys.exit(do_sheet())
    if cmd == "logo":
        sys.exit(do_logo())
    sys.exit(do_preview())

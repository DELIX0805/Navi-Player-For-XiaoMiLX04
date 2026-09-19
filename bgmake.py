"""从源图生成播放器背景资源。

纯白文字要在这块低对比度屏上可读，背景亮度必须压下来。做法不是盖一层
均匀灰（那会把图片颜色冲淡），而是**逐行自适应压暗**：按每一行最亮像素
反推该行需要的缩放系数，使白字对比度达标，同时尽量少压。

输出 res/drawable-nodpi/bg_main.png（800x480，与屏幕 1:1）并做达标校验。
"""
import os
import shutil
import sys

from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.abspath(__file__))
KEEP = os.path.join(ROOT, "bg_source.jpg")
# 默认用仓库内自带的源图；也可命令行指定任意一张：
#   python bgmake.py D:\某张图.jpg
SRC = sys.argv[1] if len(sys.argv) > 1 else KEEP
OUT = os.path.join(ROOT, "res", "drawable-nodpi", "bg_main.png")
PREVIEW = os.path.join(ROOT, "build", "bg_preview.png")

SCR_W, SCR_H = 800, 480
TARGET_CR = 5.5          # 白字目标对比度
HARD_CR = 4.5            # 最低可接受（微调兜底）


def lin(c):
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(rgb):
    return 0.2126 * lin(rgb[0]) + 0.7152 * lin(rgb[1]) + 0.0722 * lin(rgb[2])


def cr_white(l):
    return 1.05 / (l + 0.05)


def main():
    im = Image.open(SRC).convert("RGB")
    if not os.path.exists(KEEP):
        shutil.copyfile(SRC, KEEP)
        print("source kept -> %s" % KEEP)

    # 1) 铺满屏幕（比例几乎一致，基本不裁）
    scale = max(SCR_W / im.width, SCR_H / im.height)
    nw, nh = max(SCR_W, int(round(im.width * scale))), max(SCR_H, int(round(im.height * scale)))
    rs = im.resize((nw, nh), Image.LANCZOS)
    rs = rs.filter(ImageFilter.GaussianBlur(0.6))  # 抹掉 JPEG 块噪点，渐变更顺
    left, top = (nw - SCR_W) // 2, (nh - SCR_H) // 2
    bg = rs.crop((left, top, left + SCR_W, top + SCR_H))
    px = bg.load()

    # 2) 每行取抗噪的"第 5 亮"作为该行代表亮度
    row_l = []
    for y in range(SCR_H):
        vals = sorted((lum(px[x, y]) for x in range(SCR_W)), reverse=True)
        row_l.append(vals[4])

    # 3) 平滑后反推缩放系数，再逐行兜底修正
    def smooth(i, r=4):
        a, b = max(0, i - r), min(SCR_H, i + r + 1)
        return sum(row_l[a:b]) / (b - a)

    k = []
    tgt = 1.05 / TARGET_CR - 0.05
    for y in range(SCR_H):
        v = min(1.0, (tgt / max(smooth(y), 1e-6)) ** (1 / 2.2))
        # 兜底：平滑值低于局部峰值时逐次微调到达标
        for _ in range(30):
            if cr_white(max(row_l[y], 1e-6) * v ** 2.2) >= HARD_CR:
                break
            v *= 0.97
        k.append(v)

    # 4) 逐行应用
    out = bg.copy()
    for y in range(SCR_H):
        f = k[y]
        strip = out.crop((0, y, SCR_W, y + 1)).point(
            lambda v: 0 if v * f <= 0.5 else (255 if v * f >= 254.5 else int(v * f + 0.5)))
        out.paste(strip, (0, y))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out.save(OUT, "PNG", optimize=True)
    print("written %s  (%.1f KB)" % (OUT, os.path.getsize(OUT) / 1024.0))

    # 5) 校验
    op = out.load()
    worst = 99.0
    worst_y = -1
    for y in range(SCR_H):
        m = max(lum(op[x, y]) for x in range(0, SCR_W, 2))
        c = cr_white(m)
        if c < worst:
            worst, worst_y = c, y
    print("verify: min white contrast = %.2f:1 at y=%d  (target %.1f, floor %d)"
          % (worst, worst_y, TARGET_CR, HARD_CR))
    for y in (10, 120, 240, 360, 400, 440, 470):
        top_px = max((op[x, y] for x in range(SCR_W)), key=lum)
        print("   y=%3d  brightest=(%3d,%3d,%3d)  contrast=%.2f:1"
              % (y, top_px[0], top_px[1], top_px[2], cr_white(lum(top_px))))
    print("   k(top/mid/bottom) = %.3f / %.3f / %.3f" % (k[0], k[SCR_H // 2], k[-1]))

    # 6) 预览：原图 vs 处理后（上下拼接，便于目视）
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    pv = Image.new("RGB", (SCR_W, SCR_H * 2 + 8), (255, 0, 0))
    pv.paste(bg, (0, 0))
    pv.paste(out, (0, SCR_H + 8))
    pv.save(PREVIEW, "PNG")
    print("preview -> %s" % PREVIEW)


if __name__ == "__main__":
    main()

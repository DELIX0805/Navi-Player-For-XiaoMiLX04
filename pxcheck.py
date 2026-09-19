import zlib, struct, sys

def load_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', 'not png'
    pos = 8
    idat = b''
    w = h = bd = ct = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]
        typ = d[pos+4:pos+8]
        data = d[pos+8:pos+8+ln]
        pos += 12 + ln
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', data[:10])
        elif typ == b'IDAT':
            idat += data
        elif typ == b'IEND':
            break
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    assert bd == 8, 'bitdepth %d' % bd
    bpp = ch
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i-bpp]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i-bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i-bpp] if i >= bpp else 0
                pa = abs(b-c); pb = abs(a-c); pc = abs(a+b-2*c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y*stride:(y+1)*stride] = line
        prev = line
    return w, h, bpp, out

def px(buf, w, bpp, x, y):
    i = (y*w + x)*bpp
    return buf[i], buf[i+1], buf[i+2]

def scan(buf, w, h, bpp, x0, x1, y0, y1, label):
    pure = 0
    near = 0
    brightest = (0, 0, 0)
    bsum = -1
    for y in range(y0, min(y1, h)):
        for x in range(x0, min(x1, w)):
            r, g, b = px(buf, w, bpp, x, y)
            s = r + g + b
            if r == 255 and g == 255 and b == 255:
                pure += 1
            if r > 200 and g > 200 and b > 200:
                near += 1
            if s > bsum:
                bsum = s
                brightest = (r, g, b)
    print('%-12s x[%d,%d) y[%d,%d)  pure#FFFFFF=%d  >200=%d  brightest=%s'
          % (label, x0, x1, y0, y1, pure, near, brightest))

if __name__ == '__main__':
    path = sys.argv[1]
    w, h, bpp, buf = load_png(path)
    print('size %dx%d bpp=%d' % (w, h, bpp))
    # 逐行统计左侧区域白色像素，定位文字行
    rows = []
    for y in range(h):
        c = 0
        for x in range(0, min(300, w)):
            r, g, b = px(buf, w, bpp, x, y)
            if r == 255 and g == 255 and b == 255:
                c += 1
        rows.append(c)
    bands = []
    cur = None
    for y, c in enumerate(rows):
        if c > 0 and cur is None:
            cur = y
        elif c == 0 and cur is not None:
            bands.append((cur, y)); cur = None
    if cur is not None:
        bands.append((cur, h))
    print('left-column white bands (start_y,end_y):', bands)
    for (a, b) in bands:
        scan(buf, w, h, bpp, 0, 300, a, b, 'band%d' % a)

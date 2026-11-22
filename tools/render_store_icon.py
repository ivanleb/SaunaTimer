"""Renders the 512x512 store icon (F-Droid / GitHub) from the same shapes as the adaptive icon.

Usage: python tools/render_store_icon.py   (needs Pillow)
"""
from PIL import Image, ImageDraw

SIZE, SS = 512, 4  # output size, supersampling factor
N = SIZE * SS


def cubic(p0, p1, p2, p3, steps=40):
    for i in range(1, steps + 1):
        t = i / steps
        mt = 1 - t
        yield (mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
               mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1])


# Material "favorite" heart (24x24 units), converted to absolute cubic segments.
HEART = [(12, 21.35), (10.55, 20.03)]
for seg in [
    ((5.4, 15.36), (2, 12.28), (2, 8.5)),
    ((2, 5.42), (4.42, 3), (7.5, 3)),
    ((9.24, 3), (10.91, 3.81), (12, 5.09)),
    ((13.09, 3.81), (14.76, 3), (16.5, 3)),
    ((19.58, 3), (22, 5.42), (22, 8.5)),
    ((22, 12.28), (18.6, 15.36), (13.45, 20.04)),
]:
    HEART += list(cubic(HEART[-1], *seg))
PULSE = [(4.6, 11.2), (8.6, 11.2), (10, 8.3), (12.3, 14.6), (13.9, 10.2), (14.9, 11.2), (19.4, 11.2)]

# Same placement as drawable/ic_launcher_foreground.xml, in a 108-unit canvas.
SCALE, TX, TY = 2.3, 26.4, 25.3


def to_px(pt):
    k = N / 108
    return ((pt[0] * SCALE + TX) * k, (pt[1] * SCALE + TY) * k)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


img = Image.new('RGBA', (N, N))
# Diagonal gradient #FFA726 -> #D84315.
grad = Image.new('RGB', (N, N))
gp = grad.load()
start, end = (0xFF, 0xA7, 0x26), (0xD8, 0x43, 0x15)
for y in range(0, N, SS):
    for x in range(0, N, SS):
        c = lerp(start, end, (x + y) / (2 * N))
        for dy in range(SS):
            for dx in range(SS):
                gp[x + dx, y + dy] = c
mask = Image.new('L', (N, N))
ImageDraw.Draw(mask).rounded_rectangle([0, 0, N - 1, N - 1], radius=int(N * 0.22), fill=255)
img.paste(grad, (0, 0), mask)

d = ImageDraw.Draw(img)
d.polygon([to_px(p) for p in HEART], fill=(255, 255, 255, 255))
width = int(1.5 * SCALE * N / 108)
pts = [to_px(p) for p in PULSE]
d.line(pts, fill=(0xE6, 0x51, 0x00, 255), width=width, joint='curve')
for p in (pts[0], pts[-1]):
    r = width / 2
    d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=(0xE6, 0x51, 0x00, 255))

out = img.resize((SIZE, SIZE), Image.LANCZOS)
for path in ('mobile/fastlane/metadata/android/en-US/images/icon.png',
             'wear/fastlane/metadata/android/en-US/images/icon.png'):
    import os
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path, optimize=True)
    print('wrote', path)

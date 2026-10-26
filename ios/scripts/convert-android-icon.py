#!/usr/bin/env python3
"""Rasterize the existing Android launcher pulse into an opaque iOS app icon.

Requires Pillow only when regenerating the checked-in asset. No build dependency.
"""
from pathlib import Path
import math
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "NewAPIMonitor/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
SIZE = 1024
SCALE = SIZE / 108
image = Image.new("RGB", (SIZE, SIZE))
pixels = image.load()
for y in range(SIZE):
    for x in range(SIZE):
        distance = min(1, math.hypot(x / SCALE - 32.4, y / SCALE - 23.8) / 103)
        pixels[x, y] = tuple(round(a + (b - a) * distance) for a, b in zip((27, 36, 64), (10, 14, 26)))
points = [(round(x * SCALE), round(y * SCALE)) for x, y in [(30, 54), (45, 54), (52.5, 39), (61.5, 69), (69, 54), (76, 54)]]
mask = Image.new("L", image.size)
draw = ImageDraw.Draw(mask)
width = round(10 * SCALE)
draw.line(points, fill=255, width=width, joint="curve")
for x, y in points:
    radius = width / 2
    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=255)
glow = mask.filter(ImageFilter.GaussianBlur(3 * SCALE)).point(lambda p: round(p * .25))
image.paste(Image.new("RGB", image.size, (34, 211, 238)), (0, 0), glow)
gradient = Image.new("RGB", image.size)
gd = ImageDraw.Draw(gradient)
for x in range(SIZE):
    fraction = max(0, min(1, (x / SCALE - 30) / 46))
    color = tuple(round(a + (b - a) * fraction) for a, b in zip((139, 92, 246), (34, 211, 238)))
    gd.line((x, 0, x, SIZE), fill=color)
image.paste(gradient, (0, 0), mask)
OUT.parent.mkdir(parents=True, exist_ok=True)
image.save(OUT, optimize=True)
print(OUT)

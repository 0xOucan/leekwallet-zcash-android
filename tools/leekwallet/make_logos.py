#!/usr/bin/env python3
"""Replace Zodl's in-app logos (welcome screen, unlock animation) with a
LeekWallet mark: white on transparent, since the app tints them. Run from
android/. Drawn from shapes and DejaVu Sans; no third-party artwork."""
import math
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
WHITE = (255, 255, 255, 255)


def leek_mark(d, cx, top, h):
    """A leek: three leaves over a stalk with roots, `h` tall, centred on cx."""
    w = h * 0.28
    stalk_top = top + h * 0.45
    for ang in (-26, 26, 0):
        a = math.radians(ang)
        base = (cx, stalk_top + h * 0.04)
        tip = (cx + math.sin(a) * h * 0.45, top + (1 - math.cos(a)) * h * 0.2)
        half = w * 0.28
        d.polygon([(cx - half * math.cos(a), base[1] - half * math.sin(a)),
                   (cx + half * math.cos(a), base[1] + half * math.sin(a)), tip], fill=WHITE)
    d.rounded_rectangle([cx - w * 0.28, stalk_top, cx + w * 0.28, top + h * 0.9], radius=w * 0.12, fill=WHITE)
    for i in range(5):
        x = cx - w * 0.2 + i * w * 0.1
        d.line([(x, top + h * 0.9), (x + (i - 2) * w * 0.06, top + h)], fill=WHITE, width=max(2, int(h * 0.012)))


def wordmark(size, mark_h, text, font_px, path, gap=0.08):
    im = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = ImageFont.truetype(FONT, font_px)
    tw = d.textlength(text, font=f)
    if size[1] > size[0] * 0.6:          # stacked: mark above the name
        leek_mark(d, size[0] / 2, size[1] * 0.04, mark_h)
        d.text(((size[0] - tw) / 2, size[1] * 0.04 + mark_h + size[1] * gap), text, font=f, fill=WHITE)
    else:                                # inline: mark left of the name
        total = mark_h * 0.4 + size[0] * 0.03 + tw
        x0 = (size[0] - total) / 2
        leek_mark(d, x0 + mark_h * 0.2, (size[1] - mark_h) / 2, mark_h)
        d.text((x0 + mark_h * 0.4 + size[0] * 0.03, (size[1] - font_px * 1.15) / 2), text, font=f, fill=WHITE)
    im.save(path)


base = "ui-design-lib/src/main/res/ui/common"
for folder in ("drawable", "drawable-night"):
    wordmark((990, 800), 470, "LEEKWALLET", 120, f"{base}/{folder}/app_logo.png")
wordmark((633, 240), 200, "LeekWallet", 84, f"{base}/drawable/logo_with_hi.png")
wordmark((869, 192), 170, "LeekWallet", 80, f"{base}/drawable-es/logo_with_hi.png")
print("logos written")

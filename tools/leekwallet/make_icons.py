#!/usr/bin/env python3
"""Draw the LeekWallet Zcash launcher icon at every size the app ships.

Replaces Zodl's icons (their README asks forks to remove them). Drawn from
shapes, so there is no third-party artwork: a leek - white stalk, green
leaves - over a stylised "Z", on a dark background. Run from android/.
"""
import glob
import math
from PIL import Image, ImageDraw

BG = (18, 22, 28, 255)
GREEN = (76, 175, 80, 255)
DARK_GREEN = (46, 125, 50, 255)
STALK = (236, 239, 228, 255)
GOLD = (244, 183, 40, 255)


def leek(size, mono=False, background=True, inset=0.0):
    s = size * 4                      # supersample, then downscale
    im = Image.new("RGBA", (s, s), BG if background and not mono else (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pad = s * inset
    w = s - 2 * pad

    def P(x, y):
        return (pad + x * w, pad + y * w)

    ink = (255, 255, 255, 255) if mono else None
    # A golden "Z" behind, the Zcash nod.
    zc = ink or GOLD
    t = 0.07
    d.polygon([P(0.20, 0.22), P(0.80, 0.22), P(0.80, 0.22 + t), P(0.20, 0.22 + t)], fill=zc)
    d.polygon([P(0.80, 0.22 + t), P(0.80 - t * 1.4, 0.22 + t), P(0.20, 0.78 - t), P(0.20 + t * 1.4, 0.78 - t)], fill=zc)
    d.polygon([P(0.20, 0.78 - t), P(0.80, 0.78 - t), P(0.80, 0.78), P(0.20, 0.78)], fill=zc)
    # The leek: leaves fanning up, a white stalk down to the root.
    for ang, col in ((-28, ink or DARK_GREEN), (28, ink or DARK_GREEN), (0, ink or GREEN)):
        a = math.radians(ang)
        cx, cy = 0.5, 0.52
        tip = (cx + math.sin(a) * 0.40, cy - math.cos(a) * 0.40)
        half = 0.075
        nx, ny = math.cos(a) * half, math.sin(a) * half
        d.polygon([P(cx - nx, cy - ny), P(cx + nx, cy + ny), P(*tip)], fill=col)
    d.rounded_rectangle([P(0.43, 0.48), P(0.57, 0.86)], radius=s * 0.03, fill=ink or STALK)
    for i in range(5):                # roots
        x = 0.45 + i * 0.025
        d.line([P(x, 0.86), P(x + (i - 2) * 0.015, 0.92)], fill=ink or STALK, width=max(1, int(s * 0.012)))
    return im.resize((size, size), Image.LANCZOS)


def round_mask(im):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, mask=m)
    return out


base = "ui-lib/src/main/res/ui/common"
for f in glob.glob(f"{base}/mipmap-*/ic_launcher*.webp"):
    size = Image.open(f).size[0]
    if f.endswith("_foreground.webp"):
        im = leek(size, background=False, inset=0.18)   # adaptive safe zone
    elif f.endswith("_monochrome.webp"):
        im = leek(size, mono=True, background=False, inset=0.18)
    elif f.endswith("_round.webp"):
        im = round_mask(leek(size))
    else:
        im = leek(size)
    im.save(f, "WEBP", lossless=True)
leek(512).save("ui-lib/src/main/ic_launcher-playstore.png")
print("icons written")

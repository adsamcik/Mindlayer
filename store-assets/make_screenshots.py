#!/usr/bin/env python3
"""Compose Play-Store-compliant phone screenshots from raw device captures.

Raw captures are 1080x2400 (2.22:1), which exceeds Play's 2:1 max aspect ratio
for phone screenshots. This script frames each capture on a branded gradient
canvas with a marketing caption, producing 1242x2208 (1.78:1) PNGs that satisfy
Play's constraints (each side 320-3840px, ratio within 1:2..2:1).
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "screenshots", "raw")
OUT = os.path.join(HERE, "screenshots", "framed")
os.makedirs(OUT, exist_ok=True)

# Canvas: 9:16-ish portrait, comfortably within Play's ratio bounds.
CW, CH = 1242, 2208

# Brand palette (matches app MindlayerTheme indigo gradient).
TOP = (29, 0, 153)      # Indigo20  #1D0099
BOTTOM = (75, 59, 203)  # Indigo40  #4B3BCB
WHITE = (255, 255, 255)
SUBTLE = (223, 218, 255)  # Indigo90

CAPTIONS = [
    ("01-status.png", "On-device AI,", "ready when your apps are"),
    ("02-models.png", "Local models —", "nothing leaves your phone"),
    ("03-tests.png", "Text, embeddings & OCR,", "all running offline"),
]


def load_font(size, bold=True):
    candidates = [
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def vertical_gradient(w, h, top, bottom):
    base = Image.new("RGB", (w, h), top)
    draw = ImageDraw.Draw(base)
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b))
    return base


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [(0, 0), img.size], radius=radius, fill=255
    )
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def centered_text(draw, cx, y, text, font, fill):
    bbox = draw.textbbox((0, 0), text, font=font)
    w = bbox[2] - bbox[0]
    draw.text((cx - w / 2, y), text, font=font, fill=fill)
    return bbox[3] - bbox[1]


def compose(raw_name, line1, line2):
    canvas = vertical_gradient(CW, CH, TOP, BOTTOM)
    draw = ImageDraw.Draw(canvas)
    cx = CW // 2

    title_font = load_font(64, bold=True)
    centered_text(draw, cx, 120, line1, title_font, WHITE)
    centered_text(draw, cx, 200, line2, title_font, SUBTLE)

    shot = Image.open(os.path.join(RAW, raw_name)).convert("RGB")
    # Reserve top band for caption, bottom margin for breathing room.
    region_top, region_bottom = 330, CH - 70
    region_h = region_bottom - region_top
    scale = region_h / shot.height
    new_w = int(shot.width * scale)
    new_h = region_h
    if new_w > CW - 120:  # keep side margins
        scale = (CW - 120) / shot.width
        new_w = CW - 120
        new_h = int(shot.height * scale)
    shot = shot.resize((new_w, new_h), Image.LANCZOS)
    shot = rounded(shot, radius=36)

    px = (CW - new_w) // 2
    py = region_top + (region_h - new_h) // 2

    # Drop shadow.
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    sdraw.rounded_rectangle(
        [(px, py + 14), (px + new_w, py + new_h + 14)], radius=36, fill=(0, 0, 0, 110)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(22))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow)
    canvas.alpha_composite(shot, (px, py))

    out_path = os.path.join(OUT, raw_name)
    canvas.convert("RGB").save(out_path, "PNG")
    return out_path, canvas.size


if __name__ == "__main__":
    for name, l1, l2 in CAPTIONS:
        path, size = compose(name, l1, l2)
        print(f"wrote {os.path.relpath(path, HERE)}  {size[0]}x{size[1]}")

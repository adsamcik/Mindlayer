#!/usr/bin/env python3
"""Compose the 1024x500 Play feature graphic from custom hero art + wordmark.

The hero art (`feature-graphic-source.png`) is generated externally as TEXTLESS
landscape art (crystal + tech-core motif on the left, indigo gradient with
negative space on the right). This script downscales it to exactly 1024x500 and
overlays the wordmark crisply (image generators render text poorly, so the text
is composited here instead), producing an opaque RGB PNG for the Play Console.

Run from this directory:  python make_feature_graphic.py
"""

import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "feature-graphic-source.png")
OUT = os.path.join(HERE, "feature-graphic-1024x500.png")

W, H = 1024, 500
WHITE = (255, 255, 255)
SUBTLE = (200, 196, 235)  # soft violet-white for the tagline

TITLE = "Mindlayer"
TAGLINE = "On-device AI for apps you approve"
# Left edge of the text block — clear of the crystal, into the negative space.
TEXT_X = 510
RIGHT_MARGIN = 50


def load_font(size, bold=True):
    for path in (
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
    ):
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def fit_font(draw, text, start_size, max_width, bold=True):
    """Largest font (<= start_size) whose rendered text fits max_width px."""
    size = start_size
    while size > 8:
        font = load_font(size, bold=bold)
        box = draw.textbbox((0, 0), text, font=font)
        if box[2] - box[0] <= max_width:
            return font
        size -= 2
    return load_font(8, bold=bold)


def main():
    art = Image.open(SOURCE).convert("RGB").resize((W, H), Image.LANCZOS)
    draw = ImageDraw.Draw(art)

    avail = W - TEXT_X - RIGHT_MARGIN
    title_font = fit_font(draw, TITLE, 96, avail, bold=True)
    tag_font = fit_font(draw, TAGLINE, 38, avail, bold=False)

    # Vertically centre the title + tagline block in the right negative space.
    t_box = draw.textbbox((0, 0), TITLE, font=title_font)
    g_box = draw.textbbox((0, 0), TAGLINE, font=tag_font)
    title_h = t_box[3] - t_box[1]
    gap = 22
    tag_h = g_box[3] - g_box[1]
    block_h = title_h + gap + tag_h
    title_y = (H - block_h) // 2 - t_box[1]
    tag_y = title_y + t_box[1] + title_h + gap - g_box[1]

    # Subtle shadow for legibility over the gradient, then the text.
    draw.text((TEXT_X + 2, title_y + 2), TITLE, font=title_font, fill=(0, 0, 0))
    draw.text((TEXT_X, title_y), TITLE, font=title_font, fill=WHITE)
    draw.text((TEXT_X, tag_y), TAGLINE, font=tag_font, fill=SUBTLE)

    art.save(OUT, "PNG")
    img = Image.open(OUT)
    print(f"wrote {os.path.relpath(OUT, HERE)}  {img.width}x{img.height}")


if __name__ == "__main__":
    main()

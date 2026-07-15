#!/usr/bin/env python3
"""Generate the 512x512 Play Store icon from the app's adaptive-icon assets.

The Play Console "high-res icon" must match the launcher icon that installs on
the device, so it is derived directly from the in-app adaptive icon rather than
drawn fresh: the adaptive foreground art composited over the adaptive background
colour and saved as a 32-bit RGBA PNG, as required by Google Play.

Run from this directory:  python make_graphics.py
"""

import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, ".."))
FOREGROUND = os.path.join(
    REPO, "app", "src", "main", "res", "drawable-xxxhdpi", "ic_launcher_foreground.png"
)
# App adaptive-icon background colour (res/values: #FF0A0E1F -> dark navy).
ICON_BG = (10, 14, 31)


def make_icon_512():
    fg = Image.open(FOREGROUND).convert("RGBA")
    canvas = Image.new("RGBA", (512, 512), ICON_BG + (255,))
    # The foreground is a 432px (108dp) adaptive-canvas asset; scale it to the
    # full 512 square so its framing matches the launcher rendering.
    fg = fg.resize((512, 512), Image.LANCZOS)
    canvas.alpha_composite(fg, (0, 0))
    out = os.path.join(HERE, "icon-512.png")
    # Google Play requires a 32-bit PNG. Keep the alpha channel even though the
    # current composition is visually opaque.
    canvas.save(out, "PNG")
    return out


if __name__ == "__main__":
    path = make_icon_512()
    img = Image.open(path)
    print(f"wrote {os.path.relpath(path, HERE)}  {img.width}x{img.height}")

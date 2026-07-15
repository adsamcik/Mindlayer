# store-assets

Google Play listing assets for Mindlayer.

| File | What it is |
|---|---|
| `STORE_LISTING.md` | Title, short/full description (within Play char limits), Console metadata, Data Safety answers, permission rationale. Privacy-policy URL included. |
| `icon-512.png` | 512×512 high-res store icon (mandatory). Derived from the app's adaptive launcher icon so the store icon matches what installs on the device. |
| `feature-graphic-1024x500.png` | 1024×500 feature graphic (required to publish). Custom hero art with the wordmark overlaid. |
| `feature-graphic-source.png` | Textless source art for the feature graphic (1794×876), kept so the overlay can be re-rendered. |
| `make_graphics.py` | Regenerates `icon-512.png` from the in-app adaptive-icon assets. Requires Pillow. Run from this directory: `python make_graphics.py`. |
| `make_feature_graphic.py` | Downscales `feature-graphic-source.png` to 1024×500 and overlays the wordmark. Requires Pillow plus Segoe UI, Arial, or DejaVu Sans. Run: `python make_feature_graphic.py`. |
| `make_screenshots.py` | Frames raw device captures onto a Play-compliant branded canvas. Requires Pillow plus Segoe UI, Arial, or DejaVu Sans. Run from this directory: `python make_screenshots.py`. |
| `screenshots/raw/` | Raw 1080×2400 device captures (2.22:1 — **exceeds Play's 2:1 max**, reference only). |
| `screenshots/framed/` | Play-ready 1242×2208 (1.78:1) screenshots to upload. |

The current sequence shows service readiness, the local model inventory, and the
built-in engine verification surface.

The privacy policy is hosted via GitHub Pages on the `gh-pages` branch:
<https://adsamcik.github.io/Mindlayer/privacy.html>

## Still to provide before submitting

- Complete the Data Safety form (answers in `STORE_LISTING.md`).

# store-assets

Google Play listing assets for Mindlayer.

| File | What it is |
|---|---|
| `STORE_LISTING.md` | Title, short/full description (within Play char limits), Console metadata, Data Safety answers, permission rationale. Privacy-policy URL included. |
| `make_screenshots.py` | Frames raw device captures onto a Play-compliant branded canvas. Requires Pillow. Run from this directory: `python make_screenshots.py`. |
| `screenshots/raw/` | Raw 1080×2400 device captures (2.22:1 — **exceeds Play's 2:1 max**, reference only). |
| `screenshots/framed/` | Play-ready 1242×2208 (1.78:1) screenshots to upload. |

The privacy policy is hosted via GitHub Pages on the `gh-pages` branch:
<https://adsamcik.github.io/Mindlayer/privacy.html>

## Still to provide before submitting

- 512×512 app icon PNG (export from the existing launcher icon).
- 1024×500 feature graphic (optional but recommended).
- Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
- Complete the Data Safety form (answers in `STORE_LISTING.md`).

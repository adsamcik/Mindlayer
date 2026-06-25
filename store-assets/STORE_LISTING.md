# Mindlayer — Google Play store listing

Everything needed for the Play Console listing. Character limits are enforced by
Play and noted inline. Privacy-policy URL is already live (see bottom).

---

## App title  *(max 30 characters)*

```
Mindlayer: On-Device AI
```
*(23 chars)*

## Short description  *(max 80 characters)*

```
On-device AI for your apps. Fully offline, private — no cloud, no tracking.
```
*(74 chars)*

## Full description  *(max 4000 characters)*

```
Mindlayer is an on-device AI engine for Android that lets your apps run powerful AI — completely offline. It loads a large language model once and shares it with the apps you trust, so they can generate text, understand images, create text embeddings, and read text from photos, all without ever sending your data to the cloud.

WHY MINDLAYER

• Fully offline. Mindlayer does not request internet access. There is no cloud, no server, and no remote API. Everything happens on your phone.

• Private by design. No analytics, no advertising, no tracking, no accounts. Because the app has no network access, nothing about you can ever leave your device.

• You stay in control. No app can use Mindlayer until you approve it on a confirmation screen — protected by your fingerprint or face — that clearly shows which app is asking. You can deny or block any app at any time.

• Efficient. A single shared model serves all your apps, with thermal- and memory-aware scheduling that adapts to your device so it stays fast and cool.

WHAT IT CAN DO

• Chat & vision — generate text replies and describe images on-device.
• Embeddings — turn text into vectors for fast, private semantic search.
• OCR — recognise text inside photos and screenshots, locally.

Powered by Google's Gemma open model running on the LiteRT on-device runtime, plus PaddleOCR for text recognition — all open-source and fully offline.

A BUILT-IN DASHBOARD

Mindlayer includes a simple dashboard so you can see exactly what's happening: whether the service is ready, which models are loaded, how the device is performing, and which apps you've granted access to. You can run a quick on-device test at any time.

FOR DEVELOPERS

Mindlayer ships with a clean Kotlin SDK so your own apps can call on-device AI in a few lines of code — no API keys, no usage bills, no privacy compromises. One model instance serves every app on the device.

PRIVACY YOU CAN VERIFY

• No INTERNET permission — the OS itself prevents Mindlayer from making any network connection.
• Media handed to the model is encrypted while it's processed and deleted immediately afterwards.
• Diagnostic logs are stored in an encrypted database and contain only metadata — never your prompts, images, or the AI's output.
• On-device model files are integrity-checked before use.

Read the full privacy policy: https://adsamcik.github.io/Mindlayer/privacy.html

Mindlayer brings private, capable AI to your phone — and keeps it there.
```

---

## Graphics

- **App icon (512×512, mandatory):** `store-assets/icon-512.png` — derived from
  the app's adaptive launcher icon (foreground over the `#0A0E1F` background),
  so the store icon matches what installs on the device. Regenerate with
  `python store-assets/make_graphics.py`.
- **Feature graphic (1024×500, required to publish):** `store-assets/feature-graphic-1024x500.png`
  — custom textless hero art (`feature-graphic-source.png`) with the "Mindlayer"
  wordmark + tagline overlaid crisply (`python store-assets/make_feature_graphic.py`).

## Screenshots

Play-compliant phone screenshots (1242×2208, 1.78:1) are in
`store-assets/screenshots/framed/`:

1. `01-status.png` — service readiness dashboard
2. `02-models.png` — the three on-device models
3. `03-tests.png` — on-device inference tests

Raw device captures (1080×2400, 2.22:1 — **too tall for Play's 2:1 limit**, kept
for reference only) are in `store-assets/screenshots/raw/`. Regenerate the
framed set with `python store-assets/make_screenshots.py`.

> Minimum for Play: 2 phone screenshots.

---

## Console metadata (suggested)

| Field | Value |
|---|---|
| Application ID | `com.adsamcik.mindlayer` |
| Developer / publisher | adsamcik |
| Contact email | play@adsamcik.com |
| Privacy policy URL | https://adsamcik.github.io/Mindlayer/privacy.html |
| Category | Tools (or Productivity) |
| Tags | AI, on-device, privacy, offline |
| Default language | English (United States) |
| Ads | No |
| In-app purchases | No |
| Target audience | Adults (not directed at children) |

---

## Data safety form (answers)

Mindlayer collects and shares **no** user data, because it has no network access.
Answer the Console questionnaire as follows:

- **Does your app collect or share any of the required user data types?** → **No.**
- **Is all of the user data encrypted in transit?** → Not applicable (no data is
  transmitted; the app has no network permission).
- **Do you provide a way for users to request that their data be deleted?** →
  Users can clear all on-device data via Android Settings → Apps → Mindlayer →
  Storage → Clear storage, or by uninstalling. No server-side data exists.

Supporting facts (for your reference, all enforced in code):
- No `INTERNET` / `ACCESS_NETWORK_STATE` permission in the app manifest.
- No analytics, advertising, or crash-reporting SDKs.
- Inference inputs/outputs are processed in memory and not persisted by the
  service; staged media is AES-256-GCM encrypted and deleted after the request.
- Local diagnostic logs are SQLCipher-encrypted and contain metadata only.

---

## Permissions declared (for the "App access" / permissions context)

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the model alive while actively serving a visible inference request. |
| `POST_NOTIFICATIONS` | Show the required foreground-service notification during inference. |
| `USE_BIOMETRIC` | Gate app-access approval/revocation behind a device biometric check. |
| `HIDE_OVERLAY_WINDOWS` | Protect the approval screen from tap-jacking overlays. |

No location, contacts, microphone, camera, storage, or network permissions are
requested by the service.

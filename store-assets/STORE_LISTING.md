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
Private on-device AI for apps you approve — offline, shared, and open source.
```
*(77 chars)*

## Full description  *(max 4000 characters)*

```
Mindlayer is a private, on-device AI service for Android. It is not a cloud chatbot: it provides one shared local AI engine that compatible apps can use after you approve them.

RUN AI ON YOUR PHONE

• Text and image understanding — generate text and analyze images with Gemma.
• Embeddings — turn text into vectors for private semantic search.
• OCR — recognize text in photos and screenshots with PaddleOCR.
• Shared service — load the models once instead of bundling a separate AI engine into every app.

YOU CONTROL ACCESS

Every external app must request permission before it can use Mindlayer. The Mindlayer-owned consent screen identifies the requesting app and its signing certificate. When Android reports that device authentication is ready, Mindlayer shows the OS authentication prompt. Otherwise, the explicit Mindlayer confirmation screen is the approval gate. You can deny, block, or later revoke any app.

A BUILT-IN SERVICE DASHBOARD

See whether the service is ready, which local models are installed, current thermal and memory conditions, active sessions, recent diagnostic events, and the apps you have approved. Built-in checks let you verify the language model, embeddings, image understanding, and OCR directly on your device.

PRIVATE BY DESIGN

• Mindlayer does not request the Android INTERNET permission.
• No accounts, advertising, analytics, telemetry, or cloud fallback.
• Media staged for model processing is encrypted at rest and removed after the request.
• Local diagnostic logs are encrypted and contain metadata only — never prompts, images, recognized text, or model output.
• Deferred results are encrypted locally. Fetching marks a result delivered; acknowledging deletes it. Records may also be quota-evicted or expire after 24 hours by default.
• Model files are integrity-checked before use.

Mindlayer itself cannot connect to the internet. Apps you approve receive the results they request and have their own permissions and privacy practices, so only approve apps you trust.

FOR ANDROID DEVELOPERS

The open-source Kotlin SDK provides typed APIs for streaming inference, images, embeddings, OCR, deferred work, and lifecycle recovery. No API keys or usage bills are required. Mindlayer uses Google's Gemma open model with LiteRT-LM, EmbeddingGemma, and PaddleOCR.

BEFORE YOU INSTALL

Mindlayer is an AI provider service, not a standalone chat interface. You need a compatible client app to use its AI features. The included models require substantial storage, and speed and available features depend on your device hardware.

Read the full privacy policy: https://adsamcik.github.io/Mindlayer/privacy.html

Mindlayer brings shared, user-controlled AI to Android while keeping its processing on-device.
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

1. `01-status.png` — clean service readiness and device-health dashboard
2. `02-models.png` — the three shared on-device models
3. `03-tests.png` — built-in on-device engine checks

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
- Synchronous inference inputs/outputs are processed in memory and not persisted
  to service storage, though session context remains in RAM while the session is
  active. Deferred results are SQLCipher-encrypted locally. Fetching marks them
  delivered; acknowledgement deletes them. Records may also be quota-evicted or
  expire after 24 hours by default. Staged media is AES-256-GCM encrypted and
  deleted after the request.
- Local diagnostic logs are SQLCipher-encrypted and contain metadata only.

---

## Permissions declared (for the "App access" / permissions context)

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the model alive while actively serving a visible inference request. |
| `POST_NOTIFICATIONS` | Show the required foreground-service notification during inference. |
| `USE_BIOMETRIC` | Request OS authentication when Android reports it is ready. If preflight reports no enrollment, no hardware, or unavailable hardware, the explicit Mindlayer confirmation screen remains the approval/revocation gate. |
| `HIDE_OVERLAY_WINDOWS` | Protect the approval screen from tap-jacking overlays. |

No location, contacts, microphone, camera, storage, or network permissions are
requested by the service.

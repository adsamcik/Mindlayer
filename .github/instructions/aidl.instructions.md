---
applyTo: "**/aidl/**"
description: "AIDL contracts — must be byte-identical between :app and :sdk"
---

<!-- context-init:managed -->

> **Read [`docs/AIDL_STABILITY.md`](../../docs/AIDL_STABILITY.md) first** for the full evolution policy: which changes are allowed, the `schemaVersion` convention for new Parcelables, error-code allocation rules, and the `getCapabilities()` feature-flag strategy. Everything below assumes you've read that.

## AIDL invariants

- AIDL files live in **two** places and must be **byte-identical**:
  - `app/src/main/aidl/com/adsamcik/mindlayer/*.aidl`
  - `sdk/src/main/aidl/com/adsamcik/mindlayer/*.aidl`
  
  Both modules generate stubs. Drift causes `BadParcelableException` at runtime, often at the worst possible time (mid-stream).
- AIDL is **Java syntax**, not Kotlin. `package com.adsamcik.mindlayer;` (with semicolon), `interface` not `interface { }`, `in`/`out`/`inout` parameter direction.
- `IMindlayerService.aidl` lists imports for every Parcelable it references — mirror imports when adding new Parcelables.
- New Parcelables: declare in *both* modules, add the matching Kotlin/`@Parcelize` companion in `:shared` (or `:sdk` if SDK-only), and a JSON `kotlinx.serialization` representation if it crosses the pipe too. **New Parcelables get `schemaVersion: Int = 1` as their first field** — see `docs/AIDL_STABILITY.md`.
- **Existing Parcelables are frozen.** Adding a constructor parameter — even with a Kotlin default — changes wire layout and breaks old clients. Carry new data in a new Parcelable + new method instead.

## When changing AIDL, you must also update

| | |
|---|---|
| Both AIDL copies | byte-identical |
| `ServiceBinder.kt` | implementation, with `authorizeCall()` as first line of every method |
| `Mindlayer.kt` (SDK facade) | public surface |
| `ConnectionManager.kt` | only if you change `registerClient` semantics or binder-death contract |
| `IMindlayerServiceTest`, `ServiceBinderTest` | call shape, ownership rules |
| `SDK_INTEGRATION.md` | client integration impact (if user-visible) |
| `docs/AIDL_STABILITY.md` | feature-flag table, parcelable inventory, error-code allocation if any of those change |

## Media

- `ImageTransfer` and `AudioTransfer` are passed as references into `SharedMemoryPool` — never as raw `byte[]` in the parcel. The Binder transaction limit is 1 MB; an 8 MP RGBA image alone would blow it.
- The pool is owned by `:ml` and the client side stages bytes through `MediaTransfer` in `:sdk`.

## `oneway` policy

- Use `oneway` only when the caller genuinely doesn't care about completion or errors (`prewarm`).
- Default to synchronous AIDL methods so back-pressure and security exceptions reach the caller.

## Authorization

Every non-`oneway` AIDL entry point must start its implementation with `authorizeCall(...)`; session-scoped methods additionally call `requireOwnership(sessionId)`. See `security.instructions.md`.

## Typed errors

Throws on the AIDL boundary go through one of two paths:

1. **Auth gate** (`authorizeCall`, `requireRegisteredClient`, `revokeApp` self-UID, `registerClient` footguns): plain `SecurityException` with a free-form message. This stays as `SecurityException` so IDS / Play Protect doesn't lose signal.
2. **Everything else** (validation, ownership, lifecycle, quota, engine state): wire-prefixed `SecurityException` produced by `MindlayerErrorCode.wireMessage(code, message)`. The SDK chokepoint (`Mindlayer.withTypedErrors`) parses the prefix and surfaces a typed `MindlayerException`.

Adding a new throw point: pick the right code from `MindlayerErrorCode`, build the message via `wireMessage(...)` (don't hand-format the prefix), and add a test in `ServiceBinderTypedErrorsTest`. Allocating a new code? See `docs/AIDL_STABILITY.md` § "Error code allocation".


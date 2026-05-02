---
applyTo: "**/aidl/**"
description: "AIDL contracts — must be byte-identical between :app and :sdk"
---

<!-- context-init:managed -->

## AIDL invariants

- AIDL files live in **two** places and must be **byte-identical**:
  - `app/src/main/aidl/com/adsamcik/mindlayer/*.aidl`
  - `sdk/src/main/aidl/com/adsamcik/mindlayer/*.aidl`
  
  Both modules generate stubs. Drift causes `BadParcelableException` at runtime, often at the worst possible time (mid-stream).
- AIDL is **Java syntax**, not Kotlin. `package com.adsamcik.mindlayer;` (with semicolon), `interface` not `interface { }`, `in`/`out`/`inout` parameter direction.
- `IMindlayerService.aidl` lists imports for every Parcelable it references — mirror imports when adding new Parcelables.
- New Parcelables: declare in *both* modules, add the matching Kotlin/`@Parcelize` companion in `:shared` (or `:sdk` if SDK-only), and a JSON `kotlinx.serialization` representation if it crosses the pipe too.

## When changing AIDL, you must also update

| | |
|---|---|
| Both AIDL copies | byte-identical |
| `ServiceBinder.kt` | implementation, with `authorizeCall()` as first line of every method |
| `Mindlayer.kt` (SDK facade) | public surface |
| `ConnectionManager.kt` | only if you change `registerClient` semantics or binder-death contract |
| `IMindlayerServiceTest`, `ServiceBinderTest` | call shape, ownership rules |
| `SDK_INTEGRATION.md` | client integration impact (if user-visible) |

## Media

- `ImageTransfer` and `AudioTransfer` are passed as references into `SharedMemoryPool` — never as raw `byte[]` in the parcel. The Binder transaction limit is 1 MB; an 8 MP RGBA image alone would blow it.
- The pool is owned by `:ml` and the client side stages bytes through `MediaTransfer` in `:sdk`.

## `oneway` policy

- Use `oneway` only when the caller genuinely doesn't care about completion or errors (`prewarm`).
- Default to synchronous AIDL methods so back-pressure and security exceptions reach the caller.

## Authorization

Every non-`oneway` AIDL entry point must start its implementation with `authorizeCall(...)`; session-scoped methods additionally call `requireOwnership(sessionId)`. See `security.instructions.md`.

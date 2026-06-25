# Embeddings SDK polish — proposal

> Status: **proposal + minimal facade slice**. Companion PR adds the new
> facade methods and deprecates the old ones; deeper consolidation (batch
> rename, deferred wrapper, `indexBuilder`) is staged for a follow-up.
> No AIDL changes.

## 1. Inventory — what consumers see today

`com.adsamcik.mindlayer.sdk.Mindlayer` currently exposes nine public
entry points that touch embeddings, all gated on
`ServiceCapabilities.FEATURE_EMBEDDINGS`:

| # | Public method on `Mindlayer.kt` | Returns | Transport / persistence | Cap |
|---|---|---|---|---|
| 1 | `embed(text: String): FloatArray` | bare vector | inline binder | 1 input |
| 2 | `embed(config: EmbeddingConfig): com.adsamcik.mindlayer.EmbeddingResult` | parcelable | inline binder | 1 input |
| 3 | `embedBatch(configs: List<EmbeddingConfig>): List<EmbeddingResult>` | list | inline binder (`embedBatch`) | `maxEmbeddingBatchInline` (64) |
| 4 | `embedBatchLarge(configs): List<EmbeddingResult>` | list | SHM (`embedBatchShm`); falls back to inline or deferred | `maxEmbeddingBatchShm` (4096) |
| 5 | `embedBatchDeferred(configs): EmbeddingBatchHandle` | opaque handle | durable; persists in `DeferredStore`; push notification on `IClientCallback.onEmbeddingBatchComplete` | `maxEmbeddingBatchTotal` (4096) |
| 6 | `fetchEmbeddingBatch(handle): EmbeddingBatchOutcome` | sealed outcome | reads `fetchEmbeddingBatchResult` | — |
| 7 | `cancelEmbeddingBatch(handle): EmbeddingCancelResult` | typed cancel | `cancelEmbeddingBatch` AIDL | — |
| 8 | `acknowledgeEmbeddingBatch(handle)` | unit | `acknowledgeEmbeddingBatchResult` AIDL | — |
| 9 | `cancelEmbed(requestId): EmbeddingCancelResult` | typed cancel | `cancelEmbed` AIDL | — |
|   | `embeddingBatchCompletions(): Flow<String>` | hot flow of completed requestIds | callback-fed | — |

Auxiliary public types under `com.adsamcik.mindlayer.sdk.*`:
`EmbeddingConfig`, `EmbeddingModel`, `EmbeddingTask`,
`EmbeddingBatchHandle`, `EmbeddingBatchOutcome`,
`EmbeddingCancelResult`, `InMemoryVectorIndex`.

## 2. Seams — where consumers get stuck

The two pain points repeatedly hit by first-party integrators:

### 2.1 "Which batch method do I call?"

Today the consumer has to read the KDoc on three different methods,
internalise the inline / SHM / deferred tradeoff, and pick the right
one based on batch size and durability needs. This is implementation
detail leaking through the API:

- `embedBatch` — inline binder; capped at 64; blows up
  `IllegalArgumentException` above the cap, with no built-in transport
  upgrade path.
- `embedBatchLarge` — SHM; capped at 4096; *already* contains the
  inline-fallback logic when `maxEmbeddingBatchShm <= 0`. Most
  consumers should just call this. The "Large" suffix is misleading —
  it works fine for small batches too.
- `embedBatchDeferred` — durable, asynchronous, survives process death.
  Different return type, different access pattern.

The fix is single-entry: `embedMany` picks inline vs SHM under the hood
based on size + API level + service caps. Deferred stays addressable
through a distinct, explicitly-named entry point because the access
pattern (handle → fetch → ack) is genuinely different and consumers
have to opt into it.

### 2.2 "I just want a `FloatArray`"

`embed(text)` already returns a `FloatArray`. But `embed(config)`
returns the parcelable `EmbeddingResult`, and the parameter overload
makes the convenience signature easy to miss in autocomplete (both
share the name `embed`). Renaming the convenience surface to
`embedOne(text, task, modelId, ...)` makes intent explicit and gives us
named arguments for the common knobs (task, model, normalize,
outputDim) without forcing the consumer to construct an
`EmbeddingConfig`.

### 2.3 Lesser seams

- `embedBatch` returning `List<EmbeddingResult>` silently discards
  `EmbeddingBatchResult.totalDurationMs` and `.backend`. Consumers who
  want batch-level telemetry currently have no way to see it without
  going around the SDK.
- `embedBatchLarge` likewise discards the
  `EmbeddingBatchTransfer.totalDurationMs` / `.backend` aggregate.
- `EmbeddingBatchOutcome` exposes a sealed hierarchy with five
  failure variants but consumers asking "did it succeed?" have to
  pattern-match every time.

## 3. Proposed contract

Two top-level entry points plus a deferred sibling. Everything else
becomes `@Deprecated` aliases that delegate to the new shapes.

### 3.1 `embedOne`

```kotlin
/**
 * Compute one L2-normalized embedding for [text]. Returns the bare
 * vector — the typed [EmbeddingResult] (tag, modelId, tokenCount,
 * backend, duration) is reachable via [embedOneDetailed] if needed.
 */
suspend fun embedOne(
    text: String,
    task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
    modelId: String? = null,
    outputDim: Int? = null,
    normalize: Boolean = true,
    tag: String? = null,
): FloatArray
```

Delegates to `embed(EmbeddingConfig)`. Returns `result.vector`.

### 3.2 `embedMany`

```kotlin
/**
 * Compute embeddings for [items] in one trip. Transport (inline binder
 * vs SharedMemory) is chosen automatically based on batch size, payload
 * estimate, API level, and service-advertised caps. Use
 * [embedManyDeferred] for durable async batches that must survive
 * process death.
 */
suspend fun embedMany(items: List<EmbeddingConfig>): EmbeddingBatch

/** String-convenience overload. */
suspend fun embedMany(
    texts: List<String>,
    task: EmbeddingTask = EmbeddingTask.RetrievalDocument,
    modelId: String? = null,
): EmbeddingBatch
```

with

```kotlin
/**
 * Result of a synchronous [embedMany] call. Wraps the underlying list
 * of per-item [EmbeddingResult]s, exposes batch-level telemetry, and
 * records the transport that was actually used so consumers can
 * verify the SDK's choice in tests / diagnostics.
 */
class EmbeddingBatch internal constructor(
    val results: List<com.adsamcik.mindlayer.EmbeddingResult>,
    val transport: EmbeddingTransport,
    val totalDurationMs: Long,
    val backend: String,
) : List<com.adsamcik.mindlayer.EmbeddingResult> by results {
    val vectors: List<FloatArray> get() = results.map { it.vector }
}

enum class EmbeddingTransport {
    /** Inline binder transaction (small batches). */
    Inline,
    /** SharedMemory blob (mid-size, API 27+). */
    SharedMemory,
    /** SharedMemory cap was 0 (or SDK had to degrade) → durable deferred. */
    DeferredFallback,
}
```

### 3.3 Transport-selection rule

Implemented inside `embedMany`. The consumer never picks; the SDK
chooses the cheapest viable transport:

```
if (items.size > caps.maxEmbeddingBatchInline
    || estimatedReplyBytes(items) > INLINE_REPLY_BYTE_BUDGET) {
    if (Build.VERSION.SDK_INT >= 27 && caps.maxEmbeddingBatchShm > 0) {
        SharedMemory
    } else {
        DeferredFallback  // existing embedBatchLarge fallback chain
    }
} else {
    Inline
}
```

with:

- `INLINE_REPLY_BYTE_BUDGET = 512 * 1024` — half the documented 1 MB
  binder transaction limit. Inline transactions carry the *reply*
  parcel back to the SDK, so even a "small" batch (~170 × 768 floats
  ≈ 510 KB) can trip the limit; we route to SHM well before then.
- `estimatedReplyBytes(items) = items.size * (caps.embeddingDims.maxOrNull() ?: 768) * 4 + items.size * 256`
  where 256 covers parcel overhead per `EmbeddingResult` (tag,
  modelId, tokenCount, truncated, backend, durationMs).

Both the inline cap and the byte budget gate the same direction
(upgrade to SHM); the SDK picks the more conservative of the two.

The `DeferredFallback` branch reuses the existing
`embedBatchLarge` logic verbatim, which already handles
`maxEmbeddingBatchShm == 0` and oversize batches by routing through
`embedBatchDeferred` + a push/poll wait. That keeps the legacy code
path live as a single source of truth for the deferred fallback.

### 3.4 `embedManyDeferred` (rename of existing API)

```kotlin
suspend fun embedManyDeferred(items: List<EmbeddingConfig>): EmbeddingBatchHandle
```

Same return type, same semantics — just renamed for symmetry with
`embedMany`. `embedBatchDeferred` becomes a `@Deprecated` alias.
`fetchEmbeddingBatch`, `cancelEmbeddingBatch`, `acknowledgeEmbeddingBatch`,
`cancelEmbed`, `embeddingBatchCompletions` stay as-is — their names
already read well at the call site.

> **Out of scope for this PR.** Renaming the deferred entry point is
> proposed but not implemented in the minimal slice, to keep this
> change reviewable. Tracked as follow-up F-EMB-RENAME.

### 3.5 `Mindlayer.indexBuilder()` (proposed future work)

```kotlin
fun indexBuilder(): VectorIndexBuilder
// usage:
val index = mindlayer.indexBuilder()
    .add("doc1", "the cat sat on the mat")
    .add("doc2", "feline on textile")
    .buildInMemory()        // suspends; calls embedMany + populates InMemoryVectorIndex
```

`InMemoryVectorIndex` stays unchanged. The builder is sugar that
streams `embedMany` results straight into the index, so consumers do
not have to write the `for (i in texts.indices) index.put(ids[i], embedded[i])`
loop themselves. **Not implemented in this PR.** Tracked as
F-EMB-INDEX-BUILDER.

## 4. Deprecation plan

| Old method | Replacement | Behavior change |
|---|---|---|
| `embed(text: String)` | `embedOne(text)` | None. New method delegates here. |
| `embed(config: EmbeddingConfig)` | `embedOneDetailed(config)` *(see note)* | None. |
| `embedBatch(configs)` | `embedMany(items)` | None at the binder layer. New method picks `embedBatch` for small batches. |
| `embedBatchLarge(configs)` | `embedMany(items)` | None at the binder layer. New method picks `embedBatchShm` for batches above the inline cap or byte budget. |
| `embedBatchDeferred(configs)` | (no rename in this PR — see §3.4) | n/a |

All old methods stay functional. Each becomes
`@Deprecated(message, ReplaceWith(...), level = DeprecationLevel.WARNING)`
and its body is left intact so the new facades delegate *down* to the
old ones (which already encapsulate the binder calls). This means:

- Source-incompatible? **No.** Existing call sites still compile, with a
  warning.
- Binary-incompatible? **No.** Methods retain identical bytecode signatures.
- Semantically equivalent? **Yes** — the new facades are pure
  delegators in this slice.

### `embedOneDetailed` note

`embed(EmbeddingConfig)` is the only existing method whose return type
the new facade actively wants to *change* (to a bare `FloatArray`). To
keep the typed surface reachable, the proposal introduces
`embedOneDetailed(config): EmbeddingResult` as the new home for the
parcelable-returning variant. **Not implemented in this PR**, because
the existing `embed(EmbeddingConfig)` already serves that role and
deprecating it without a replacement would force consumers off it
prematurely. Tracked as F-EMB-DETAILED.

## 5. Error and capability contract — single table

Every embedding entry point obeys this table:

| Failure scenario | Code | `MindlayerException.code` | Source method |
|---|---|---|---|
| Service doesn't advertise `FEATURE_EMBEDDINGS` | 5006 | `NOT_SUPPORTED` | `requireEmbeddingCapability` |
| Old service stub: `NoSuchMethodError` / `AbstractMethodError` on call | 5006 | `NOT_SUPPORTED` | per-method `catch` |
| Empty `items` list | — | `IllegalArgumentException` (eagerly) | `validateEmbeddingBatchSize` |
| `items.size > caps.maxEmbeddingBatchInline` for an `Inline` route | — | `IllegalArgumentException` | `validateEmbeddingBatchSize` |
| `items.size > caps.maxEmbeddingBatchShm` for a `SharedMemory` route | — | `IllegalArgumentException` | `validateEmbeddingBatchSize` |
| `items.size > caps.maxEmbeddingBatchTotal` for a `DeferredFallback` route | — | `IllegalArgumentException` | `validateEmbeddingBatchSize` |
| Service-side batch too large past validation | 5011 | `EMBEDDING_BATCH_TOO_LARGE` | service `infer` path |
| Service-side input bytes too long | 5013 | `EMBEDDING_INPUT_TOO_LONG` | service preprocessing |
| Embedding model unavailable on device | 5012 | `EMBEDDING_MODEL_UNAVAILABLE` | service engine init |
| Embeddings disabled by policy | 5014 | `EMBEDDING_DISABLED` | service config gate |
| Deferred batch fetched after TTL | 5008 | `DEFERRED_EXPIRED` | `fetchEmbeddingBatch` |
| Deferred batch not owned by this UID | 5004 | `SESSION_NOT_FOUND_OR_NOT_OWNED` | `fetchEmbeddingBatch` |
| Rate-limited (per-UID quota) | 5002 | `RATE_LIMITED` | service authz gate |
| `SecurityException` from authz | varies | wrapped by `MindlayerException.fromAidlSecurityException` | `withTypedErrors` |

This table is mirrored into the class-level KDoc on `Mindlayer.kt` so
consumers don't have to read each method's KDoc independently.

## 6. `InMemoryVectorIndex` integration

**Recommendation: keep, document role explicitly, add a streaming
builder later.** The index is a 100-line pure-Kotlin client-side
cosine helper — there is nothing to break and nothing the service
layer needs to know about. The current doc already warns about scope
(< 10K vectors, no persistence, no ANN). The only addition needed is
`Mindlayer.indexBuilder()` (see §3.5), which is staged as follow-up
work to keep this PR reviewable.

The class-level KDoc on `InMemoryVectorIndex` will not change.

## 7. AIDL boundary

**No AIDL changes in this PR.** All facade methods delegate to existing
public methods that already wrap existing AIDL calls. The wire surface
is unchanged. Future work that *would* touch AIDL (e.g. a transport
hint parameter, or a unified `embedMany` AIDL method) is out of scope
and would have to follow the rules in `docs/architecture/AIDL_STABILITY.md`
(append-only, capability-gated).

## 8. What this PR ships vs. what it proposes

### Ships in this PR

1. This proposal document (`docs/sdk/EMBEDDINGS_SDK_POLISH.md`).
2. `Mindlayer.embedOne(...)` facade — delegates to `embed(EmbeddingConfig)`.
3. `Mindlayer.embedMany(items: List<EmbeddingConfig>)` and
   `Mindlayer.embedMany(texts: List<String>, ...)` facades — pick
   inline vs SHM via the §3.3 rule, delegate to existing
   `embedBatch` / `embedBatchLarge`.
4. New return type `EmbeddingBatch` + `EmbeddingTransport` enum.
5. `@Deprecated(ReplaceWith=...)` on `embed(text)`, `embedBatch`,
   `embedBatchLarge` pointing at the new facades. Bodies preserved.
6. Embeddings section in the class-level KDoc on `Mindlayer.kt`
   referencing the error/capability table.
7. Unit tests pinning: facades exist, deprecated annotations present,
   transport selection picks SHM above the inline cap, transport
   selection falls back to inline on API ≤ 26.

### Deferred to follow-up PRs

- `embedOneDetailed` (currently the existing `embed(EmbeddingConfig)`
  serves this role).
- `embedManyDeferred` rename (existing `embedBatchDeferred` stays).
- `Mindlayer.indexBuilder()` (proposed in §3.5).
- Promoting `@Deprecated` from `WARNING` to `ERROR` — only after one
  release cycle of adoption.
- Removing the deprecated methods — earliest **two** release cycles
  after they go to `ERROR`.

## 9. Rollback plan

Every change here is additive. To roll back:

1. Remove `embedOne` and `embedMany` from `Mindlayer.kt`.
2. Remove `EmbeddingBatch.kt` and `EmbeddingTransport.kt`.
3. Remove the `@Deprecated` annotations.
4. Delete this doc and the new test file.

No consumer code breaks — the underlying methods were never altered.

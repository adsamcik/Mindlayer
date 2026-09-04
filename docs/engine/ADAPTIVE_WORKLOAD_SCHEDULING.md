# Adaptive LLM workload scheduling

Mindlayer admits LLM work through one bounded, non-preemptive scheduler before
the single native LiteRT-LM conversation slot. This changes which queued request
runs next; it does not change model output, sampling, token limits, ownership,
or rate limits.

## Dispatch order

At each lease boundary the scheduler chooses:

1. any request waiting at least 30 seconds, oldest first;
2. otherwise, requests matching the currently warm conversation;
3. higher app-supplied priority (`-10..10`);
4. lower estimated work;
5. FIFO sequence as the deterministic tie-breaker.

Warm affinity avoids closing and reconstructing a conversation when useful
session state is already resident. After four consecutive dispatches for one
affinity, another waiting session gets a turn. This burst cap plus the 30-second
age override prevents a busy interactive client from starving background work.

The cost estimate starts with conservative input-token accounting and adds
fixed penalties for image input, audio, thinking, and tool-loop capability.
These are ordering heuristics, not billed or reserved tokens. They should be
recalibrated from benchmark latency, but bad estimates can only affect order;
they cannot grant more context or bypass admission limits.

## SDK intent

Apps may set a coarse priority on the request builder:

```kotlin
val handle = mindlayer.infer {
    session(sessionId)
    text(query)
    priority(InferencePriority.INTERACTIVE)
}
```

Use `BACKGROUND` for indexing or maintenance, `NORMAL` by default,
`INTERACTIVE` for user-blocking work, and `URGENT` only for a genuinely more
important user-visible request. Priority never interrupts active native work.
The existing per-UID and global request limits are enforced before this queue,
so clients cannot use priority to expand their admitted workload.

## Scope and model affinity

The current production portfolio has one generative artifact and LiteRT-LM
allows one warm conversation. Therefore the concrete affinity key is the warm
session ID: this is the state whose reuse currently avoids native close/replay.
When Mindlayer supports multiple generative artifacts, the scheduler key should
become a composite of engine family, artifact hash, backend, context class, and
session. Artifact/model affinity must remain ahead of estimated cost.

Embedding and OCR retain independent adaptive single-writer schedulers. An
interactive retrieval query ranks above background document indexing, then
shorter text runs first. OCR uses pixel count as its cost signal, so a smaller
queued frame runs before a larger one. Both retain aging protection. Globally
serializing auxiliary engines behind the LLM would discard currently supported
coexistence and could increase latency without memory evidence. A future
memory-pressure arbiter may delay *cold initialization* of an auxiliary engine,
but should not change this concurrency contract until the Play-oriented
benchmark measures the peak and lifecycle trade-off.

## Cancellation and lifecycle

Queued coroutine cancellation atomically removes its ticket. Media and pipe
resources are cleaned even if cancellation happens before a native lease.
Session destruction is checked again under the admitted session mutex, so work
staged before destruction cannot recreate a native conversation afterward.

The queue is deliberately in-memory and bounded by existing service quotas. It
does not persist private prompt content, and logs should report only aggregate
queue timing/affinity outcomes—not prompts or model inputs.

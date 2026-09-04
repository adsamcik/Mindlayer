# LLM prewarm context budget

## Decision

Mindlayer's no-budget prewarm APIs allocate a maximum of **76 MiB of KV
cache**, which maps to **8,192 context tokens** using the repository's
conservative 9.5 KiB/token estimate. They do not initialize at the device
tier's 32K-131K maximum.

Speculative prewarm is skipped when either condition is true:

- kernel-reported device RAM is at most 4 GiB;
- current memory pressure is `WARNING`, `CRITICAL`, or `EMERGENCY`.

An app that knows its next workload may call
`prewarmForContext(maxTokens, backend)`. This is an explicit opt-in on low-RAM
devices, although the service still clamps the request to its live pressure
ceiling. The SDK accepts 128-32,768 tokens for both prewarm and session
configuration, so an app can request a larger context than the 8K automatic
budget when its actual input requires one.

## First-party workload audit

The default is based on the inputs first-party applications actually send, not
on the model's theoretical context maximum.

| Application | LLM input shape | Largest configured context | Finding |
|---|---|---:|---|
| StarlitCoffee | OCR-first coffee-bag extraction, translation, combination and refinement; long structured-output system prompt; typical OCR text is about 400-1,500 tokens | 8,192 | Deciding workload. Its own source notes roughly 3,743 tokens of prompt reservation; 4,096 leaves too little input/output room and has produced truncated JSON. |
| Ledgit | Receipt extraction with bounded fields/items, import-schema inference with samples capped at 4,000 characters, and short title/category/text tasks | 2,048 | 8K supplies substantial headroom. Long receipt text is summarized or truncated before generation. |
| Riposte | OCR and embedding generation | Not applicable | It does not create chat sessions, so it has no LLM KV-context requirement. |

Audited source roots:

- `G:\Github\StarlitCoffee\app\src\main\java\com\adsamcik\starlitcoffee\data\network\llm\MindlayerLlmInferenceProvider.kt`
- `G:\Github\Ledgit\Android` Mindlayer generators and strategy runners
- `G:\Github\Riposte` Mindlayer OCR and embedding adapters

## Why the default is not 16K

Doubling the observed largest workload would permanently reserve roughly
another 76 MiB of anonymous KV memory whenever the engine is warm. That is a
poor hedge against an uncertain workload estimate under Android 17 process
limits and Google Play Anonymous RSS + Swap thresholds.

Instead, underestimation is handled dynamically. Before session creation the
service compares the requested effective context with the live engine's
initialization context. A larger request:

1. rejects new inference with retryable `ENGINE_INITIALIZING`;
2. lets existing inference drain;
3. invalidates engine-bound sessions;
4. persists the larger context and current backend;
5. restarts the `:ml` process rather than closing/recreating LiteRT-LM in the
   same process;
6. lets the SDK reconnect and retry the session against the fresh engine.

This restart is required while LiteRT-LM issue #2028 makes in-process engine
recreation unsafe. If active inference does not drain within 60 seconds, the
resize is abandoned and retried later rather than killing work mid-decode.
Session creation keeps a 120-second retry window so that the 60-second safe
drain and a realistic cold model initialization can both complete.

The same native-lifetime constraint shapes idle cleanup. Once all clients are
hidden and work is idle, independently reloadable embedding and OCR engines are
closed after 30 seconds. After five minutes the SDK and service atomically
coordinate a final unbind and terminate `:ml`; the next visible transition or
API call starts a fresh process. Idle exit does not persist a restart intent or
immediately prewarm, because either would defeat memory reclamation.

## Measurement boundary

The 9.5 KiB/token relationship comes from the repository's LiteRT-LM 0.12
empirical work and is intentionally rounded up. It is a sizing input, not proof
of current LiteRT 2.2 anonymous RSS on shipping devices. Release validation
still needs real-device measurements of Anonymous RSS + Swap, total RSS,
file-backed pages, GPU/driver allocations, and cold/peak/steady inference by
RAM tier and process state.

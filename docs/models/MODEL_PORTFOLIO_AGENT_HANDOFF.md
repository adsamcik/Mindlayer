# Mindlayer model-portfolio agent handoff

Copy and paste the complete block below into the agent that will continue this
work. It contains only the decision-relevant information from the five research
reports.

```text
You are taking over the final analysis of Mindlayer's Android model portfolio.
Produce a decision-ready synthesis and a minimal evidence plan. Do not repeat a
broad model-landscape survey and do not implement changes. Resolve the remaining
choices, identify what is actually proven, and specify the smallest experiments
needed before any production change.

RESEARCH STATE

Five independent reports were completed on 4 September 2026:

1. current and emerging model landscape;
2. Android RAM accounting and optimization;
3. workload-native model fit and evaluation;
4. runtime, conversion, licensing, and model-shipping feasibility;
5. benchmark and release-gating design.

Their external claims were researched on that date but have not been reproduced
inside Mindlayer. Revalidate any decisive time-sensitive claim from primary
sources. Treat vendor benchmarks, community conversions, emulator runs, and
issue reports as candidate evidence, not Mindlayer production proof.

PRODUCT BASELINE

Mindlayer is a privacy-first, offline Android inference service used by client
apps through a Kotlin SDK over AIDL, pipes, and SharedMemory. Models run in a
separate service process without production network permission. Models are
delivered on demand through Google Play, verified by size and SHA-256, and
materialized in private storage.

Supplied stack snapshot:

- Android min SDK 26; compile/target SDK 37; arm64 production focus.
- Gemma 4 E2B Instruct through LiteRT-LM 0.16.1 for text/image/audio.
- EmbeddingGemma 300M through LiteRT 2.2.0, with asymmetric query/document
  usage, 768 native dimensions, 512/256/128 reduced dimensions, and a
  2,048-token input ceiling.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation models
  through LiteRT 2.2.0.
- Generative, embedding, and OCR runtimes can share one Android service process.
- One generative model is selected at a time and at most one native conversation
  is kept warm.
- CPU, GPU, selected NPU/Google Tensor, multiple SoC vendors, OS/driver
  versions, lifecycle behavior, and accelerator fallback all matter.
- Emergency memory recovery may require service-process restart because native
  teardown/recreation has historically been unsafe or incomplete.
- Offline operation, deterministic artifacts, hash pinning, compatible
  redistribution rights, Play delivery, fallback, observability, and rollback
  are hard requirements.

CONSUMER WORKLOADS

Coffee-bag recognition:

- OCR-first multilingual/noisy-label processing.
- Strict, evidence-grounded JSON for roaster, coffee name, origin,
  producer/farm/region, variety, process, tasting notes, dates, weight,
  altitude, and decaf.
- Correct abstention and low user-correction burden matter more than fluency.
- The current path may use several text passes, an 8,192-token engine ceiling,
  and selective image inference.

Meme retrieval:

- Multilingual asymmetric retrieval over OCR text, titles, descriptions,
  phrases, template, content, intent, emotion, emoji, and differentiator.
- Current storage is up to five float32 768-dimensional vectors per meme.
- Background indexing, interactive queries, caches, hybrid FTS/vector ranking,
  vector/index RAM, and atomic full reindexing matter.

Financial-document understanding:

- Offline receipt, ticket, and import understanding from client-side OCR.
- Strict JSON or constrained labels for kind, vendor, total, currency, date,
  line items, ticket fields, category, title, transaction splitting, and import
  schemas.
- Roughly 2,048-token context with output budgets around 64–1,024 tokens.
- Exact financial values, abstention, schema validity, and correction burden
  matter more than conversational quality.

CONSENSUS FROM THE REPORTS

1. Do not replace a production model yet.
2. Conditionally retain Gemma 4 E2B, EmbeddingGemma 300M, and PP-OCRv5 while
   measuring and documenting the exact existing artifacts.
3. Implement FAST_TEXT and QUALITY_TEXT first as separate bounded Gemma 4 E2B
   execution profiles. Test shorter prompts, smaller task-specific contexts,
   one grounded extraction pass, and at most one validator-targeted repair
   before adding another resident generator.
4. Preserve immutable OCR evidence. Models should select or normalize grounded
   candidates; deterministic validators may reject or request review but must
   not invent semantic corrections.
5. For meme retrieval, first test EmbeddingGemma at 768, 512, and 256
   dimensions. The leading compression hypothesis is three 256-dimensional
   float16 semantic slots instead of five 768-dimensional float32 slots. This
   is a challenger, not an accepted design; retrieval quality and index
   working-set measurements decide.
6. PP-OCRv6 Small is the leading OCR replacement experiment. Keep the current
   orientation component initially so detector/recognizer effects remain
   isolated.
7. A general vision-language model should not replace OCR-first processing by
   default. Vision must demonstrate enough reduction in correction burden to
   justify encoder RAM, image buffers, latency, and lifecycle risk.
8. Workload-native results dominate generic benchmark scores. The portfolio,
   not an isolated model, is the release unit.

PRIORITIZED MODEL EXPERIMENTS

First tier:

- FAST_TEXT bakeoff under identical prompts, validators, contexts, and devices:
  compact Gemma 4 E2B profile, Gemma 3 1B, Granite 4.0 H 350M, and Qwen3 0.6B.
- FunctionGemma 270M on one narrow low-entropy task such as document kind plus
  schema selection; it requires task-specific tuning and is not a general
  replacement.
- PP-OCRv6 Small conversion, preprocessing/dictionary parity, downstream
  extraction quality, and Android RAM tests.
- EmbeddingGemma 768/512/256 quality, storage, index-RAM, and latency tests
  before funding a new embedding runtime.
- Desktop reference evaluation of Granite Embedding 97M Multilingual R2 and
  Harrier-OSS-v1-270M before Android conversion.
- Gemma 4 E4B only on the hardest coffee and financial slices.
- Calibrate a deterministic trigger for using Gemma 4 E2B vision after OCR.

Conditional or later:

- LFM2.5 1.2B only after its commercial/revenue-conditioned license is confirmed
  acceptable and its language/workload advantage is demonstrated.
- Qwen2.5 1.5B only if its quality gain offsets artifact size and delivery cost.
- PaddleOCR-VL only as a difficult-document evidence producer or if complex
  layout understanding becomes a product requirement.
- Qwen3.5 0.8B only after credible broad Android GPU memory and runtime-state
  evidence exists.
- Direct image embeddings for memes only after text-only retrieval limits are
  demonstrated and API/reindex costs are accepted.

Reject or defer now:

- Do not add Phi-4-mini, FastVLM, large Granite variants, or other multi-GB
  models without a new workload requirement and measured advantage.
- Do not deploy a base FunctionGemma without task tuning.
- Do not infer Android compatibility from ONNX, TensorFlow, StableHLO, or TFLite
  export alone.
- Do not reindex the production meme corpus for a benchmark-only improvement.
- Do not add a second inference runtime solely to try an unproven candidate.

RAM CONCLUSION

There is no defensible single RAM number yet. The decision quantity is a
measured envelope:

client-process plus service-process PSS
+ verified non-overlapping accelerator memory
+ a bounded estimate of unobserved driver/system memory.

Index it by exact artifact, quantization, context capacity, live prompt/output
tokens, modality, backend, device/SoC, OS/driver, initialization order,
co-resident resources, process state, and lifecycle phase.

Historical x86_64 CPU-emulator data using an older LiteRT-LM reported roughly:

- 127 MiB process baseline;
- 474 MiB fixed engine/model PSS above baseline;
- 9,208 bytes additional PSS per configured context token.

Use those values only as a regression hypothesis. They do not prove current
arm64, GPU/NPU, app-plus-service, LiteRT-LM 0.16.1, or real-device behavior.

HIGHEST-PRIORITY UNKNOWNS

1. Exact source revisions, converted artifact hashes, quantization/calibration,
   tokenizer/chat template, schemas, dictionaries, and conversion flags for all
   existing models.
2. Effective context signatures in each packaged artifact and whether memory is
   reserved by configured capacity, grows with live tokens, or grows in quanta.
3. Fresh, warm, peak, and post-close residual memory for generative,
   embedding, OCR, and realistic combinations.
4. Actual GPU/NPU execution versus silent fallback and memory not visible in
   ordinary process PSS.
5. Same-process LiteRT 2.2.0 and LiteRT-LM 0.16.1 behavior across all
   initialization orders, concurrency, cancellation, teardown, model/backend
   switching, and process recreation.
6. Current workload-native baseline quality. No candidate has yet been measured
   on held-out coffee, meme, or financial-document corpora.
7. Actual device fleet, minimum supported RAM tier, process state, LMK behavior,
   Android 17 memory limits, and OEM variance.
8. API-26 IPC behavior because Android SharedMemory begins at API 27.
9. Bundle-generated compressed Play-pack sizes, temporary free-space needs,
   atomic upgrade, offline rollback, and whether the baseline artifact fits
   current delivery limits.
10. PP-OCRv5 artifact provenance and PP-OCRv6 conversion feasibility/parity.
11. Embedding/index crossover at realistic library sizes and atomic migration
   between index generations.
12. License and redistribution clearance for every weight, derivative artifact,
   tokenizer, converter, patch, and evaluation corpus.

MANDATORY EVIDENCE ORDER

Use deny-by-default, dependency-ordered gates:

G0. Primary-source, license, and product-fit triage.
G1. Immutable source/artifact manifest and reproducible acquisition.
G2. Conversion plus source-to-converted parity.
G3. Android CPU smoke and deterministic known-answer checks.
G4. Small held-out workload corpus and early rejection.
G5. Fresh-process RAM/context/token/modality sweep.
G6. Real-device GPU/NPU execution, memory, and observable fallback.
G7. Generative/embedding/OCR coexistence and initialization-order matrix.
G8. Repeated lifecycle, cancellation, pressure, and residual-memory tests.
G9. Full workload corpora and required ablations.
G10. Sustained thermal, energy, background/foreground, and LMK testing.
G11. Deterministic Play delivery, integrity, upgrade, and rollback.
G12. Privacy-safe staged field rollout.

A weighted score cannot compensate for failing legal, integrity, privacy,
correctness, memory, crash, compatibility, or rollback gates. Every run must
record requested and observed backend separately; unexpected fallback fails the
run.

DECISIONS YOU MUST RESOLVE

1. The exact first compact-text bakeoff and whether Granite 350M should precede
   Gemma 3 1B, Qwen3 0.6B, or FunctionGemma.
2. Whether LFM, Qwen2.5, and PaddleOCR-VL merit any near-term work after license,
   delivery, conversion, and portfolio-complexity costs.
3. The smallest viable coffee and financial context profiles based on actual
   token distributions, not advertised context.
4. The embedding dimension, numeric format, semantic-slot count, and index
   design to test first.
5. Whether PP-OCRv6 should be the only near-term OCR challenger.
6. Whether process isolation beyond the current service is justified by
   lifecycle/reclamation evidence rather than assumed to be beneficial.

REQUIRED OUTPUT

Return only:

1. A one-page decision memo using NOW, NEXT EXPERIMENT, LATER, and
   REJECT/DEFER.
2. One compact portfolio table: capability, current baseline, challenger,
   reason, blocker, and decisive experiment.
3. A dependency-ordered list of no more than ten experiments. For each give the
   hypothesis, minimum inputs/devices, measurements, acceptance/stop criterion,
   artifact produced, and decision unlocked.
4. The hard production gates.
5. Contradictions or stale claims that require primary-source revalidation.
6. A clear statement of what can be implemented immediately versus what must
   remain research-only.

Do not bury the recommendation in background material. Do not present estimates
as measurements. Do not call emulator, conversion, dependency, or isolated
backend success production proof. State exact evidence boundaries.
```

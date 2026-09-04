# Model fit, Android compatibility, and RAM research prompts

Prepared from the current Mindlayer architecture and the observed workloads of
three consumer applications. These prompts are deliberately self-contained:
the research agent does not need repository access and must not invent missing
implementation details.

Every fenced prompt below is standalone and ready to copy and paste without
adding a shared preamble. Use either:

- the master prompt for one end-to-end research report; or
- any focused prompt independently for a narrower report, followed optionally
  by the standalone synthesis prompt.

The supplied stack snapshot is dated **2026-09-04**. Every version, model,
artifact, benchmark, license, and compatibility claim is time-sensitive and
must be verified as of the research date.

## Master prompt

```text
Act as a principal on-device ML systems researcher. Produce an evidence-backed,
decision-ready report identifying the best current and near-term on-device
model portfolio for Mindlayer and its consumer workloads, with special focus on
real Android RAM use, reliability, and compatibility. You have no repository access.
Treat the implementation snapshot below as supplied project context,
not as proof that any external claim is still current.

RESEARCH DATE AND EVIDENCE RULES

1. State the date on which you performed the research and search the live web.
2. Prioritize primary sources: official model cards, papers, repositories,
   release notes, runtime documentation, conversion tools, issue trackers, and
   Android/Google Play documentation. Use credible independent benchmarks to
   supplement—not replace—primary sources.
3. Prefer evidence from the last 12 months, but include older foundational work
   when it materially changes the recommendation.
4. Cite every externally verifiable claim near the claim with a direct link and
   publication/release date. Do not cite search-result pages.
5. Label each important claim as one of: VERIFIED (direct authoritative or
   reproducible evidence), REPORTED (credible third-party result), INFERRED
   (derived from stated facts), or UNKNOWN (no adequate evidence).
6. Never equate parameter count, download size, or nominal quantization with
   Android peak RAM. Never equate a model's advertised context window with a
   context size that is safe on a phone.
7. Never call a model compatible merely because its source architecture can be
   exported. Distinguish an existing tested Android artifact from a plausible
   conversion path and from speculative support.
8. When sources disagree, show the disagreement and explain which evidence you
   trust and why. Do not fill missing measurements with fabricated precision.

PRODUCT AND STACK SNAPSHOT

Mindlayer is a privacy-first, offline Android inference service. It loads model
resources in a separate service process and serves multiple client apps through
a Kotlin SDK over AIDL, pipes, and Android SharedMemory. It has no network
permission in production. Model artifacts are delivered on demand through
Google Play asset delivery, verified by size and SHA-256, and materialized in
private storage. Release builds must not load arbitrary untrusted models.

The current generative baseline is Gemma 4 E2B Instruct in a `.litertlm`
container through LiteRT-LM. It supports text, image, and audio, although the
main production consumer paths are mostly OCR-first and text-only. The service
currently exposes one selected generative model and keeps at most one native
conversation warm at a time. Changing a model, context ceiling, or accelerator
may require teardown or process recreation; repeatedly loading multiple
generative engines is not assumed safe or efficient.

The dated runtime snapshot is:

- Kotlin/Android service; min SDK 26, compile/target SDK 37.
- LiteRT-LM 0.16.1 for generative inference.
- base LiteRT 2.2.0 `CompiledModel` for embeddings and OCR.
- EmbeddingGemma 300M, 768 native dimensions with Matryoshka-style supported
  output dimensions of 768, 512, 256, and 128; maximum input currently 2,048
  tokens.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation models.
- CPU, GPU, and selected NPU/Google Tensor paths are relevant. Backend support
  varies by model, runtime, SoC, driver, ABI, and Android release.
- arm64 Android devices are the main production target; x86_64 remains useful
  for emulator validation. Do not assume emulator success proves real-device
  acceleration, thermal behavior, or memory safety.
- LiteRT-LM and base LiteRT coexist in one service process. The embedding model,
  three OCR models, and generative engine can therefore interact through native
  libraries, linker namespaces, thread pools, delegates, GPU/NPU resources, and
  allocator behavior. Sequential and concurrent coexistence both matter.
- A known Android namespace workaround is currently required for the paired
  LiteRT artifacts. Re-check whether current releases still require it.
- The service lazily initializes resources, unloads embeddings and OCR under
  serious memory pressure, evicts sessions, reduces context ceilings, and may
  restart its process at emergency pressure to reclaim native state safely.
- Model delivery, integrity pinning, offline operation, AGPL-compatible product
  distribution, and the upstream model license/redistribution terms are hard
  feasibility constraints.

HISTORICAL MEMORY BASELINE—REMEASURE, DO NOT GENERALIZE

An older x86_64 Android emulator/CPU experiment using Gemma 4 E2B and
LiteRT-LM 0.12.0 reported approximately:

- 127 MiB process baseline;
- 474 MiB fixed engine-plus-model PSS above baseline;
- 9,208 bytes of additional PSS per configured context token, with a near-linear
  fit over the measured range;
- less than 1 MiB per Conversation because the engine appeared to preallocate a
  shared KV pool.

Measurements read `/proc/self/smaps_rollup`, Android `Debug.MemoryInfo`, and
native heap, sampled at 250 ms during initialization, and used a fresh process
per point because close/recreate contaminated later baselines. This evidence is
useful only as a methodology and regression baseline. Re-test current runtime,
model, backend, ABI, device, prompt modality, and context lengths. Investigate
whether current runtimes changed KV layout, allocation timing, cache reuse,
weight mapping, or teardown behavior.

CONSUMER WORKLOADS

A. Coffee-bag recognition (interactive, privacy-sensitive)

- Android min SDK 26.
- The primary pipeline is OCR-first: PaddleOCR extracts label text, then a
  generative model normalizes multilingual/noisy OCR and extracts structured
  coffee metadata. Image inference is optional and should be used only when its
  quality gain justifies its RAM and latency.
- Labels can contain English, Czech, German, Italian, French, other languages,
  missing diacritics, merged words, unusual proper nouns, processing methods,
  varieties, altitude ranges, dates, and weights.
- The output is strict structured JSON covering fields such as roaster, coffee
  name, country/origin, producer/farm/region, varieties, processing method,
  tasting notes, roast/expiry dates, weight, altitude, and decaf status.
- Correct abstention and evidence grounding matter more than filling every
  field. The model must not confuse recipe doses/temperatures with bag weight
  or altitude, or confuse roaster, producer, farm, and product name.
- Users review and edit results before saving. The metric that matters is
  reduction in correction burden and completed scans, not generic chat quality.
- Current inference uses stateless one-shots, low-temperature structured output,
  and an 8,192-token engine ceiling. It may run normalization, extraction,
  combine, refinement, or a single optional vision pass. Research whether a
  smaller prompt/model/profile can reduce that ceiling and number of passes.
- Candidate routing concepts are FAST_TEXT, QUALITY_TEXT, and VISION, but these
  are workload profiles rather than hardcoded model identities.

B. Meme search and indexing (retrieval-heavy, high volume)

- Android min SDK 31.
- The core task is multilingual asymmetric semantic retrieval for user queries
  against meme metadata: title, OCR text, description, search phrases, template,
  emotion, and emoji meaning. Languages include at least English, Czech, German,
  Spanish, and Portuguese.
- The current model is EmbeddingGemma 300M. Query and document task prefixes are
  distinct. The app stores up to five vectors per meme (content, intent, emotion,
  emoji, differentiator), currently as float32 768-dimensional blobs, and keeps
  query caches. Indexing runs in background batches; query embedding is
  interactive. Search combines FTS and semantic scores and must degrade to FTS
  when ML is unavailable.
- OCR uses a general-document profile for screenshot/scene text. There is no
  direct image-embedding API today; image-derived search relies on OCR and
  precomputed text descriptions.
- Research must evaluate retrieval quality, multilingual robustness, query
  latency, batch throughput, model RAM, output dimensionality, vector storage
  and working-set RAM, quantization of stored vectors, number of vectors per
  item, caching, candidate retrieval/index design, and the cost of full reindex
  on a model or prompt-version change.

C. Expense, receipt, ticket, and import understanding (structured extraction)

- Android min SDK 34.
- Tesseract performs OCR locally; ML Kit handles language ID/translation and
  barcodes. Generative inference is delegated to Mindlayer rather than loading
  another app-local LLM.
- Tasks include document-kind classification (receipt/ticket/generic), vendor,
  total, currency and date extraction, receipt line-item extraction, ticket
  fields, multi-page documents, splitting multiple transactions, expense
  category classification, short title generation, and detecting/validating/
  refining CSV/Markdown/plain-text import schemas.
- Most outputs are strict JSON or one constrained label. Current task output
  budgets range from about 64 tokens for classification to 1,024 for line items
  and import schemas. The current receipt pipeline assumes a 2,048-token model
  context, summarizes or chunks longer OCR, and uses low-temperature inference.
- Accuracy on totals, currencies, dates, line items, and abstention is more
  important than open-ended fluency. Sensitive financial data must remain
  offline. Do not propose silent keyword/rule fallbacks as the primary answer;
  deterministic validation around model output is welcome.

RESEARCH QUESTIONS

1. Model portfolio: Which current models are best for (a) compact structured
   text extraction/classification, (b) higher-quality text extraction, (c)
   optional vision/audio, (d) multilingual retrieval embeddings, and (e) OCR?
   Determine whether one general model or a small specialized portfolio gives
   the best total product result.
2. Cutting edge: Identify relevant advances from the last 12–18 months in
   mobile-sized language, vision-language, embedding, OCR, document-understanding,
   hybrid recurrent/state-space/transformer, small MoE, and task-specialized
   models. Explain which advances actually reduce phone RAM or latency and which
   merely improve benchmark scores or active-parameter marketing.
3. Compatibility: For every shortlisted model, classify it as:
   A—official tested LiteRT/LiteRT-LM Android artifact;
   B—community Android artifact with reproducible evidence;
   C—convertible with a documented, reproducible toolchain and supported ops;
   D—requires bounded runtime/converter work;
   E—blocked or speculative.
4. Conversion: When conversion is needed, trace source checkpoint -> exporter ->
   quantization -> tokenizer/chat template -> LiteRT or LiteRT-LM container ->
   Android runtime. Identify unsupported ops, dynamic shapes, custom kernels,
   tokenizer/template issues, multimodal preprocessing, calibration needs, ABI
   constraints, and parity tests. Do not treat ONNX/TFLite export alone as proof
   of LiteRT-LM chat compatibility.
5. Licensing and delivery: Check weights license, conversion-tool license,
   redistribution, derivative weights, commercial/Play distribution, attribution,
   gated download, and whether deterministic builds can pin hashes. Assess on-
   demand pack layout, update size, free-space needs, and whether reconstruction
   creates avoidable disk or RAM duplication.
6. RAM model: Decompose memory into Java/Kotlin heap, native heap, file-backed
   weight mappings, private/dirty pages, shared pages, KV cache, activations,
   attention/prefill scratch, tokenizer, sampler, image/audio encoders, OCR
   tensors, embedding tensors, delegate/compiler caches, GPU memory, NPU/shared
   buffers, IPC copies, image bitmaps, and allocator fragmentation. Explain what
   Android PSS/RSS does and does not reveal.
7. Memory techniques: Evaluate model-size reduction, weight/activation/KV
   quantization, GQA/MQA, local/sliding-window attention, recurrent or hybrid
   architectures, context caps per task, prompt/schema compaction, chunked
   prefill, prefix/KV reuse, paged or bounded KV where supported, buffer reuse,
   memory mapping, zero-copy inputs, delegate cache policy, sequential resource
   scheduling, unload/reload thresholds, process isolation/restart, and avoiding
   co-resident engines. State runtime support and tradeoffs for each—do not give
   a generic optimization checklist.
8. Quality/efficiency tradeoffs: Determine whether tiny generative models are
   genuinely useful for the supplied extraction tasks. Compare them with
   compact encoders/classifiers, OCR-specific models, constrained decoding,
   deterministic parsers around ML output, and selective escalation to a larger
   model. Include model-switch cold-start and fragmentation cost.
9. Embedding efficiency: Compare EmbeddingGemma with the strongest compatible
   current alternatives. Test whether 128/256/512 Matryoshka dimensions,
   float16/int8 vector storage, fewer semantic slots, late-interaction methods,
   ANN indexes, or better fusion can reduce RAM/storage without unacceptable
   retrieval loss. Keep model RAM distinct from index and vector RAM.
10. Device routing: Propose capability-based profiles using total RAM, current
    available memory, LMK threshold, recent backend failures, thermal state,
    installed packs, context requirement, and measured model/backend costs.
    Static parameter count or total-RAM labels alone are not sufficient.

REQUIRED MEASUREMENT PLAN

Design a reproducible Android harness that measures each candidate in a fresh
process and in realistic service reuse. At minimum capture:

- baseline, post-load steady state, peak initialization, peak first inference,
  warm inference, post-request steady state, post-close residual, and repeated
  load/run/close cycles;
- PSS, RSS, Private_Dirty, Private_Clean, Shared_Clean, SwapPss, Java heap,
  native heap, graphics/GPU where observable, file mappings, thread count, file
  descriptors, LMK/kill evidence, and app-plus-service total memory;
- context sweeps sufficient to fit fixed footprint and bytes-per-token slope,
  plus output-length and prompt-length sweeps rather than only configured max;
- text, image, audio, embedding sequence length/dimension/batch size, and OCR
  image-size/line-count sweeps as applicable;
- CPU, GPU, supported NPU/Google Tensor, and mixed-backend configurations;
- initialization order and coexistence permutations for generative,
  embedding, and OCR engines, both sequential and concurrent;
- cold start, warm time-to-first-token, prefill throughput, decode throughput,
  end-to-end task latency, sustained thermal/battery behavior, and failures;
- representative 4, 6, 8, 12, and 16+ GB devices across Qualcomm, MediaTek,
  Google Tensor, and at least one constrained/older arm64 device where feasible;
- exact model hash, quantization, runtime build, backend, device/SoC, Android
  build, driver, prompt/schema version, and corpus revision in every result.

Explain how to observe GPU/NPU allocations when public Android APIs are
insufficient, and mark measurements that are not comparable across vendors.
Define warm-up, quiescence, sampling cadence, repetitions, confidence intervals,
and stop conditions. Separate emulator validation from real-device evidence.

REQUIRED QUALITY EVALUATION

Define workload-native evaluations rather than relying on generic leaderboards:

- coffee bags: per-field exact/normalized precision and recall, correct
  abstention, unsupported-value rate, evidence validity, structured-output
  validity, correction burden, completion rate, multilingual/noisy OCR slices,
  and incremental value of vision;
- meme search: nDCG@k, MRR, Recall@k, zero-result rate, latency, multilingual and
  slang/emoji/template slices, query/document asymmetry, storage per item, full
  indexing time/energy, and quality sensitivity to dimensions/quantization/slot
  count/index method;
- expenses/documents: exact normalized vendor/amount/currency/date accuracy,
  line-item precision/recall, document-kind and category macro-F1, schema
  validity, JSON parse rate, abstention, multi-page accuracy, multilingual OCR,
  and user correction burden.

Use identical inputs and deterministic validation across models. Report mean,
tail, and failure distributions, not only successful medians. Include prompt
and conversion parity checks. Recommend acceptance gates, but distinguish
proposed thresholds from measured results.

REQUIRED REPORT STRUCTURE

1. Executive recommendation: the recommended production portfolio now, one or
   two experimental candidates, and a watchlist. Say plainly if the current
   baseline should remain.
2. Workload-to-model routing table, including fallback/degradation behavior.
3. Candidate matrix with exact model/version, release date, total and active
   parameters, architecture, modalities, languages, context, artifact format,
   quantization, file size, measured or estimated RAM with evidence label,
   Android/runtime status, accelerators, license, and blockers.
4. Compatibility ladder (A–E) with exact conversion/integration steps.
5. RAM accounting model and optimization table. For every optimization show
   expected benefit, affected memory component, required runtime support,
   quality/latency cost, implementation effort, risk, and how to verify it.
6. Consumer-specific analysis for coffee bags, meme retrieval, and financial
   documents.
7. Reproducible benchmark and corpus plan with acceptance gates.
8. Device/profile routing proposal and model-delivery implications.
9. Ranked roadmap: NOW, NEXT EXPERIMENT, LATER, and REJECT/DEFER, with exit
   criteria and rollback conditions.
10. Known unknowns and the smallest experiments that resolve them.
11. Source appendix grouped into official models, runtimes/converters, papers,
   Android/platform, licenses, and independent measurements.

DECISION DISCIPLINE

- Score models per workload, not with one global leaderboard. Show weights and
  a sensitivity analysis so reasonable weight changes do not hide a fragile
  winner.
- A smaller model must demonstrate a material device-coverage, RAM, latency, or
  energy advantage while remaining useful after deterministic validation.
- A vision tier must show incremental task quality sufficient to justify its
  encoder memory, image buffers, latency, and stability risk.
- Include total portfolio cost: downloads, disk, RAM, initialization, model
  switching, reindexing, QA matrix, licenses, and operational complexity.
- Prefer capability/profile routing over exposing concrete model IDs to client
  apps.
- Do not recommend production promotion without a reproducible artifact,
  quality gate, real-device memory/latency result, teardown/coexistence result,
  integrity pin, license review, and rollback path.
```

## Focused research set

Each prompt repeats the context it needs. Do not combine prompts unless a single
researcher is intentionally being asked to cover multiple tracks.

### Prompt 1 — current model and architecture landscape

```text
You are a principal on-device ML model researcher. Produce an evidence-backed,
decision-ready landscape report identifying the strongest current and emerging
models for an offline Android inference platform and its three concrete
consumer workloads. This is not a generic list of small models.

You have no repository access. Everything you need about the product is stated
below. Do not ask for source files and do not invent implementation details.

RESEARCH DATE AND EVIDENCE RULES

1. State your research date and search the live web.
2. Prioritize official model cards, papers, repositories, release notes,
   runtime/converter documentation, issue trackers, and license texts. Use
   independent benchmarks only as supporting evidence.
3. Prefer evidence from the last 12–18 months while retaining older
   foundational work when still technically relevant.
4. Cite every version-sensitive or externally verifiable claim near the claim
   with a direct link and source date. Do not cite search-result pages.
5. Mark material claims VERIFIED, REPORTED, INFERRED, or UNKNOWN. Explain
   disagreements between sources and do not fabricate precise RAM estimates.
6. Keep model download size, parameter count, active parameter count, weight
   RAM, KV-cache RAM, activation/scratch RAM, and Android peak PSS distinct.
7. An exportable architecture is not automatically Android-compatible.
   Distinguish an official tested artifact, a reproducible community artifact,
   a documented conversion path, bounded runtime work, and speculation.

SUPPLIED PRODUCT CONTEXT

Mindlayer is a privacy-first Android inference service. It runs models offline
in a separate service process and exposes them to client applications through
a Kotlin SDK using AIDL, pipes, and Android SharedMemory. Production has no
network permission. Models are delivered on demand through Google Play asset
delivery, checked by size and SHA-256, and materialized in private storage.
Release builds cannot depend on arbitrary untrusted local models.

The supplied stack snapshot is dated 2026-09-04 and must be refreshed during
research:

- Android min SDK 26 and compile/target SDK 37; arm64 is the production focus.
- Gemma 4 E2B Instruct in a LiteRT-LM container through LiteRT-LM 0.16.1 for
  text, image, and audio generation.
- EmbeddingGemma 300M through base LiteRT 2.2.0, with 768 native dimensions,
  Matryoshka-style 512/256/128 dimensions, and a 2,048-token input ceiling.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation models
  through base LiteRT 2.2.0.
- Generative, embedding, and three OCR models can share one service process,
  native libraries, delegates, accelerators, caches, and allocators.
- CPU, GPU, selected NPU/Google Tensor paths, multiple SoC vendors, and x86_64
  emulator validation matter. Emulator success is not real-device proof.
- One generative model is selected at a time and at most one native
  conversation is kept warm. Model/backend changes may require teardown or
  process recreation.
- Offline operation, Android runtime feasibility, deterministic conversion,
  artifact hashing, Google Play delivery, graceful fallback, and compatible
  redistribution rights are hard constraints.

CONSUMER WORKLOADS

A. Coffee-bag recognition: interactive OCR-first processing of multilingual,
noisy label text followed by evidence-grounded strict-JSON extraction of
roaster, coffee name, origin, producer/farm/region, varieties, process, tasting
notes, dates, weight, altitude, and decaf status. Correct abstention and low
user-correction burden matter more than generic fluency. An optional image pass
exists. Current text inference may use several passes and an 8,192-token engine
ceiling; smaller FAST_TEXT and QUALITY_TEXT profiles are desirable if quality
survives.

B. Meme search: high-volume multilingual asymmetric retrieval over OCR text,
titles, descriptions, search phrases, template, emotion, intent, and emoji
meaning. Languages include English, Czech, German, Spanish, and Portuguese.
The baseline is EmbeddingGemma 300M with distinct query/document prefixes and
up to five float32 768-dimensional vectors per meme. Background indexing,
interactive queries, caches, hybrid FTS/vector ranking, vector storage, and
full reindex cost matter. There is no direct image-embedding API today.

C. Expense/document understanding: Tesseract OCR and ML Kit language,
translation, and barcode features feed text inference for receipt/ticket/
generic classification, vendor/total/currency/date and line-item extraction,
expense categories, short titles, multi-transaction splitting, and import-
schema detection/validation/refinement. Outputs are strict JSON or constrained
labels. Current tasks assume roughly 2,048 context tokens with output budgets
of about 64–1,024 tokens. Accuracy and abstention on sensitive financial data
matter more than conversational quality.

PRIMARY RESEARCH TASK

Research the strongest current and newly emerging on-device model candidates
for these workloads. Cover compact generative text models, higher-quality
mobile text models, vision-language/audio models, asymmetric multilingual
embedding models, OCR/document-understanding models, and genuinely relevant
hybrid, recurrent, state-space, local-attention, and small-MoE advances from the
last 12–18 months.

Do not start from a fixed list or assume the newest model is best. Seed the
search with current Gemma variants and EmbeddingGemma, then search broadly
across vendors and open model ecosystems. Include specialized models when they
could replace an expensive general-model pass.

For each serious candidate provide exact version/date, architecture, total and
active parameters, modalities, languages, context, tokenizer/template,
quantization options, official artifact formats, license/redistribution terms,
and primary benchmark evidence relevant to extraction or retrieval. Explain
which architectural properties plausibly affect weight RAM, KV RAM, activation
RAM, prefill cost, or mobile acceleration.

Classify Android feasibility as: official tested LiteRT/LiteRT-LM artifact;
reproducible community artifact; documented conversion likely supported;
bounded converter/runtime work; or blocked/speculative. Cite direct primary
sources. Output a workload-specific shortlist, experimental list, watchlist,
and explicit rejects. Identify facts that require hands-on conversion or device
measurement before any choice can be made.

REQUIRED REPORT

Return the report in this exact order:

1. Executive recommendation: best production portfolio now, up to three
   experiments, watchlist, and explicit rejects. Say whether the supplied
   baseline should remain while experiments run.
2. Workload-to-capability routing table covering FAST_TEXT, QUALITY_TEXT,
   optional VISION/AUDIO, EMBEDDING, and OCR, including fallback behavior.
3. Candidate matrix with exact model/version, release date, architecture,
   total and active parameters, modalities, languages, context, artifact and
   quantization, file size, RAM evidence status, license, Android feasibility
   class, likely accelerators, and material blockers.
4. Separate analyses for compact text generation, quality text generation,
   multimodal generation, embeddings, and OCR/document understanding.
5. Cutting-edge advances table stating the practical mobile benefit, runtime
   dependency, evidence strength, tradeoff, and relevance to these workloads.
6. Compatibility ladder with the exact artifact or proposed conversion chain
   for every shortlisted candidate.
7. Per-workload scorecards. Declare criteria and weights, then show whether the
   winner changes under reasonable weight variations.
8. Unknowns and the smallest conversion, quality, and real-device experiments
   needed to resolve each one.
9. Ranked actions under NOW, NEXT EXPERIMENT, LATER, and REJECT/DEFER.
10. Source appendix grouped into model cards, papers, runtimes/converters,
    licenses, and independent measurements.

DECISION STANDARD

Do not select one model merely because it leads a generic benchmark. Optimize
the whole portfolio across extraction or retrieval quality, reliable
abstention, peak RAM, latency, energy, download/disk cost, initialization and
switching cost, reindexing, licensing, and operational complexity. Do not
recommend production promotion without a reproducible artifact, conversion
parity, workload-native quality evidence, real-device memory/latency evidence,
coexistence/teardown evidence, license review, integrity pin, and rollback path.
```

### Prompt 2 — Android RAM accounting and optimization

```text
You are a principal Android native-memory and on-device inference performance
engineer. Produce a research-backed RAM model, measurement specification, and
prioritized optimization program for Mindlayer. The result must be detailed
enough for another engineer to implement the benchmark harness without access
to the original repositories.

You have no repository access. Use only the supplied product context below for
project facts. Do not ask for source files or fill gaps with invented details.

RESEARCH DATE AND EVIDENCE RULES

1. State your research date and search current official documentation, runtime
   releases, repositories, issue trackers, papers, and Android platform sources.
2. Cite each external or version-sensitive claim with a direct link and source
   date. Prefer primary sources; identify independent measurements explicitly.
3. Mark claims VERIFIED, REPORTED, INFERRED, or UNKNOWN.
4. Distinguish allocated versus resident memory, clean mapped weights versus
   private dirty pages, per-process versus system/driver memory, configured
   context capacity versus used tokens, and steady state versus transient peak.
5. Never derive peak Android RAM from parameter count, quantized file size, or
   advertised context length alone.
6. State when public Android tooling cannot observe a memory component and
   provide a bounded measurement or triangulation method instead of pretending
   PSS is complete.

SUPPLIED SYSTEM CONTEXT

Mindlayer is a privacy-first offline Android inference service. Client apps use
a Kotlin SDK over AIDL, pipes, and Android SharedMemory. Models execute in a
separate service process with no production network permission. Google Play
delivers model packs on demand; artifacts are checked by size and SHA-256 and
materialized in private storage.

The supplied stack snapshot is dated 2026-09-04 and must be revalidated:

- Android min SDK 26, compile/target SDK 37, principally arm64 devices.
- Gemma 4 E2B Instruct in a LiteRT-LM container through LiteRT-LM 0.16.1.
- EmbeddingGemma 300M through LiteRT 2.2.0, with up to 2,048 input tokens and
  768/512/256/128 output dimensions.
- Three PaddleOCR PP-OCRv5 mobile models for detection, recognition, and
  orientation through LiteRT 2.2.0.
- Generative, embedding, and OCR stacks can occupy the same process and share
  native libraries, delegates, GPU/NPU resources, thread pools, caches, file
  mappings, and allocators.
- CPU, GPU, selected NPU/Google Tensor, Qualcomm, MediaTek, and emulator paths
  matter. Different backends may allocate memory outside ordinary app PSS.
- One generative model is selected at a time and no more than one native
  conversation is kept warm.
- Resources are initialized lazily. Serious memory pressure unloads embeddings
  and OCR, evicts sessions, and lowers context ceilings. Emergency handling may
  cancel work and restart the service because native teardown/recreation has
  historically left unsafe or unreclaimed state.

Historical data is a regression hypothesis, not a current result. An x86_64
Android 16 CPU-emulator experiment with Gemma 4 E2B and LiteRT-LM 0.12.0
reported approximately 127 MiB process baseline, 474 MiB fixed engine/model PSS
above baseline, and 9,208 bytes additional PSS per configured context token.
It sampled proc/self/smaps_rollup, Debug.MemoryInfo, and native heap every
250 ms and used a fresh process for each point because close/recreate
contaminated later baselines. Reproduce the method on current versions and real
devices; do not generalize these numbers.

The service must support three workload shapes:

- Interactive OCR-first coffee-label normalization and strict extraction,
  currently capable of several passes and an 8,192-token ceiling, with an
  optional image pass.
- Interactive query embedding plus high-volume background meme indexing,
  PaddleOCR, caches, and up to five float32 768-dimensional stored vectors per
  item.
- Mostly 2,048-context receipt/ticket/import extraction with short constrained
  outputs, OCR supplied by the client, and sensitive data kept offline.

PRIMARY RESEARCH TASK

Perform a systems-level RAM investigation for this service. Build a complete
memory accounting model for generative, embedding, OCR, IPC, image/audio, and
runtime resources in one Android process. Keep file size, virtual address space,
RSS, PSS, USS where available, private dirty/clean, shared mappings, Java heap,
native heap, graphics/GPU allocations, NPU/shared buffers, KV cache,
activations, prefill/decode scratch, tokenizer/sampler state, compiler/delegate
caches, image/audio encoders, media buffers, IPC copies, and fragmentation
distinct.

Audit current advances and runtime features that could reduce memory: weight,
activation and KV quantization; GQA/MQA; sliding/local attention; recurrent or
hybrid architectures; context caps by task; prompt/schema compaction; chunked
prefill; prefix/KV reuse; paged/bounded KV; buffer/tensor reuse; mmap and direct
file loading; zero-copy IPC/media; delegate serialization/cache choices;
sequential scheduling; resource unloading; separate processes; and deliberate
process restart. For each, verify whether current LiteRT, LiteRT-LM, Android,
and shortlisted models actually support it. Give expected direction/magnitude
only when evidence exists, plus quality, latency, thermal, complexity, and
stability tradeoffs.

Design a reproducible benchmark harness with fresh-process and service-reuse
phases, context/modality/batch sweeps, backend and initialization-order matrix,
250 ms or better peak sampling where justified, repeated trials, confidence
intervals, teardown-residual checks, app-plus-service totals, and real-device
coverage across RAM tiers and SoC vendors. Explain how to capture or bound
GPU/NPU memory when Android PSS is incomplete. Finish with a prioritized
optimization plan ranked by likely benefit, confidence, effort, and risk.

REQUIRED EXPERIMENT DESIGN

The harness must measure:

- idle process baseline; post-runtime and post-model-load steady state; peak
  initialization; peak first inference; warm inference; post-request steady
  state; post-close residual; and repeated load/run/close cycles;
- prompt-length, output-length, and configured-context sweeps that can separate
  fixed cost from bytes per live or reserved token;
- text prefill/decode, image size/count, audio length, embedding
  sequence/dimension/batch, and OCR image-size/line-count sweeps as applicable;
- CPU, GPU, every genuinely supported NPU path, mixed backends, initialization
  order, sequential coexistence, overlap/concurrency, cancellation, memory
  pressure, and client-process plus service-process totals;
- cold/warm time to first token, prefill and decode throughput, end-to-end task
  latency, batch throughput, thermal throttling, energy where defensible,
  failures, low-memory kills, and backend fallbacks;
- representative 4, 6, 8, 12, and 16+ GB devices across Qualcomm, MediaTek,
  Google Tensor, and at least one older constrained arm64 device;
- exact model hash, quantization, runtime build, ABI, backend, device/SoC,
  Android build, driver, prompt/schema, and corpus revision for every result.

Define warm-up, quiescence, sampling cadence, number of repetitions, confidence
intervals, outlier policy, comparable versus non-comparable measurements, and
stop conditions. Separate emulator validation from real-device evidence.

REQUIRED REPORT

Return the report in this exact order:

1. Executive findings and the five highest-value next actions.
2. Memory-component diagram or table showing ownership, lifetime,
   observability, likely scaling variable, and reclamation mechanism.
3. Interpretation guide for PSS, RSS, Private_Dirty, Private_Clean,
   Shared_Clean, SwapPss, Java/native heap, mmap, dmabuf/graphics, GPU, NPU, and
   client-plus-service accounting.
4. Current-runtime capability audit for each proposed optimization. Include
   required model/runtime/backend support and direct evidence.
5. Fully specified experiment matrix and instrumentation procedure, including
   commands/APIs where authoritative and safe.
6. Results schema and formulas for fixed footprint, per-token slope, peak
   overhead, teardown residual, co-residency overhead, and confidence bounds.
7. Device/profile routing proposal based on measured cost, available memory,
   LMK thresholds, thermal state, backend health, installed packs, and requested
   context rather than parameter count alone.
8. Ranked optimization backlog with target memory component, expected benefit,
   evidence confidence, quality/latency/energy impact, engineering effort,
   compatibility risk, validation experiment, exit criterion, and rollback.
9. Known unknowns and the smallest experiment that resolves each.
10. Source appendix grouped into Android/platform, LiteRT/LiteRT-LM, model
    architecture, memory techniques, and independent measurements.

Do not claim an optimization works merely because it exists in another
runtime. Do not recommend reducing a context ceiling until the real prompts and
output budgets are measured. Optimize peak app-plus-service memory and
reliability, not just the easiest metric to report.
```

### Prompt 3 — workload-native model fit and evaluation

```text
You are a principal applied-ML evaluation lead specializing in private,
on-device Android products. Design a complete, workload-native model-selection
and evaluation program for Mindlayer and its three consumer use cases. Your
answer must enable a team to build corpora, run comparable evaluations, and
select pipeline shapes without relying on generic chat leaderboards.

You have no repository access. Use the supplied product description as the
authoritative project context. Do not request code or invent missing behavior.

RESEARCH DATE AND EVIDENCE RULES

1. State the research date and browse current primary sources for candidate
   models, tokenizers, output constraints, embedding behavior, OCR, runtimes,
   evaluation methods, and recent relevant papers.
2. Cite external claims with direct links and source dates. Label important
   claims VERIFIED, REPORTED, INFERRED, or UNKNOWN.
3. Keep published benchmark performance separate from expected product quality
   and from quality actually measured on the proposed corpora.
4. Keep parameter count, file size, steady and peak RAM, latency, energy, and
   model-switch overhead as separate decision variables.
5. Treat prompt, chat template, tokenizer, quantization, conversion, decoding,
   validator, OCR source, and corpus version as part of the evaluated system.
6. Do not use production user data unless a privacy-preserving collection,
   consent, retention, and redaction plan is explicitly defined.

SUPPLIED PLATFORM CONTEXT

Mindlayer is an offline Android inference service used through a Kotlin SDK over
AIDL, pipes, and SharedMemory. The service owns model delivery, integrity,
consent, inference lifecycle, accelerator fallback, thermal behavior, and
memory-pressure recovery. Production has no network permission.

The dated 2026-09-04 baseline uses Gemma 4 E2B Instruct through LiteRT-LM
0.16.1, EmbeddingGemma 300M through LiteRT 2.2.0, and PaddleOCR PP-OCRv5 mobile
through LiteRT 2.2.0. The embedding baseline supports asymmetric query/document
task prefixes, 768 native dimensions, 512/256/128 Matryoshka-style dimensions,
and inputs up to 2,048 tokens. Generative, embedding, and OCR resources can
share one Android service process, so extra passes, model switching, vision,
and simultaneous indexing have RAM and latency costs. All current versions and
capabilities must be independently refreshed.

WORKLOAD A — COFFEE-BAG RECOGNITION

- Interactive, privacy-sensitive, OCR-first processing. PaddleOCR text is
  normalized and transformed into strict structured JSON. A single image pass
  may be used selectively if its incremental value is demonstrated.
- Inputs include English, Czech, German, Italian, French, and other languages;
  noisy OCR; missing diacritics; merged words; uncommon proper nouns; origin
  hierarchies; varieties; processes; dates; altitude; and weight.
- Fields include roaster, coffee name, country/origin, producer, farm, region,
  varieties, processing method, tasting notes, roast/expiry dates, weight,
  altitude, and decaf status.
- High-cost mistakes include inventing absent values, confusing roaster with
  producer/farm/product, and treating recipe doses or temperatures as bag
  weight or altitude.
- Users review and edit before saving. Correction burden and completed scans
  matter more than open-ended fluency.
- The current pipeline may normalize, extract, combine, refine, or use optional
  vision, with low-temperature one-shots and an 8,192-token engine ceiling.
  Candidate profiles are FAST_TEXT, QUALITY_TEXT, and VISION.

WORKLOAD B — MEME SEARCH AND INDEXING

- Multilingual asymmetric semantic retrieval over title, OCR text, description,
  search phrases, template, content, intent, emotion, emoji meaning, and
  differentiator. Languages include at least English, Czech, German, Spanish,
  and Portuguese, plus slang and culturally specific references.
- The current system stores up to five float32 768-dimensional vectors per
  meme. Query and document prefixes differ. Background batches build vectors;
  query embedding is interactive; query caches and hybrid FTS/semantic ranking
  are used; FTS remains a graceful fallback.
- OCR handles screenshots and scene text. There is no direct image-embedding
  API, so image search depends on OCR and existing textual descriptions.
- Dimension reduction, float16/int8 storage, fewer slots, vector/index design,
  caching, batch behavior, model or prompt versioning, and full reindex cost
  must be evaluated together with retrieval quality.

WORKLOAD C — EXPENSE, RECEIPT, TICKET, AND IMPORT UNDERSTANDING

- Tesseract performs local OCR; ML Kit provides language ID/translation and
  barcode features; Mindlayer handles text inference. Financial data remains
  offline.
- Tasks include document-kind classification, vendor/total/currency/date,
  receipt line items, ticket fields, multi-page inputs, splitting multiple
  transactions, expense category, short title, and CSV/Markdown/plain-text
  import-schema detection, validation, and refinement.
- Outputs are constrained labels or strict JSON. Current task output budgets
  range from roughly 64 to 1,024 tokens. The current pipeline assumes a
  2,048-token model context and chunks or summarizes longer OCR.
- Totals, currencies, dates, line items, schema validity, abstention, and
  correction burden matter more than conversational quality. Deterministic
  validation around the model is desired; silent keyword/rule substitution for
  the model task is not.

PRIMARY RESEARCH TASK

Determine which model classes, concrete current candidates, prompt strategies,
and pipeline shapes best fit each workload. Do not reuse generic leaderboard
ranks. Propose realistic evaluation corpora, metrics, prompt/output contracts,
deterministic validators, and acceptance gates for:

1. multilingual/noisy coffee-label OCR normalization and evidence-grounded
   structured extraction, including selective vision escalation;
2. multilingual meme query-to-metadata retrieval, including dimension,
   quantized-vector, semantic-slot, cache, ANN/index, and reindex tradeoffs;
3. receipts, tickets, generic documents, line items, expense categories,
   titles, transaction splitting, and import schemas with constrained output.

For tiny generative models, explicitly test whether task decomposition,
constrained decoding, shorter schemas, or specialized classifiers/encoders make
them useful. Account for the latency and memory cost of extra passes or model
switching. Separate model errors from OCR errors and from deterministic parsing
errors. Measure abstention, hallucination/unsupported values, JSON validity,
tail latency, failures, and user correction burden—not just average accuracy.

Output a workload-to-capability matrix, candidate scoring rubrics with declared
weights and sensitivity analysis, minimum viable gates, failure/degradation
behavior, and the smallest experiment set that can eliminate weak candidates
before expensive device testing.

REQUIRED EVALUATION CONTENT

For coffee bags, include per-field exact and normalized precision/recall,
correct abstention, unsupported-value rate, evidence validity, JSON/schema
validity, correction actions and time, completion rate, multilingual/noisy OCR
slices, field-confusion matrices, multi-pass ablation, context/prompt
compaction, and the incremental quality and resource cost of vision.

For meme retrieval, include nDCG@k, MRR, Recall@k, zero-result and irrelevant-
top-result rates, query latency, indexing throughput/energy, multilingual,
slang, emoji, intent, emotion, template, and difficult-negative slices. Ablate
embedding model, dimensions, numeric storage, normalization, semantic slot
count, fusion weights, caches, ANN/exact index, and OCR/description availability.
Report storage per item, index working set, model RAM, and full reindex cost
separately.

For financial documents, include exact normalized vendor/amount/currency/date
accuracy, line-item precision/recall and arithmetic consistency, document-kind
and category macro-F1, ticket field accuracy, transaction-split accuracy,
schema validity, JSON parse rate, abstention, hallucinated-value rate,
multi-page and long-input behavior, multilingual OCR slices, and user
correction burden.

Across all workloads:

- use identical inputs and deterministic validators across model comparisons;
- separate OCR, model, conversion/tokenization, and post-processing errors;
- include clean, noisy, ambiguous, missing-value, adversarial, and
  out-of-distribution slices;
- report mean, median, tails, failure distributions, and paired uncertainty
  rather than successful-case averages alone;
- prevent training/public-benchmark contamination where possible and define a
  held-out decision set;
- include quality-versus-context, quality-versus-RAM, and
  quality-versus-latency frontiers;
- define early rejection gates before full real-device benchmarking.

REQUIRED REPORT

Return the report in this exact order:

1. Executive recommendation by workload and capability profile.
2. Candidate and pipeline-shape shortlist, including specialized encoders or
   classifiers, general small language models, escalation models, embeddings,
   OCR, and optional vision.
3. Workload-to-capability routing matrix with fallback/degradation behavior.
4. Corpus specification for each workload: sampling frame, sources,
   annotation schema, ambiguity policy, languages/slices, minimum sizes,
   privacy handling, splits, versioning, and leakage controls.
5. Prompt, structured-output, deterministic validation, and error-taxonomy
   specifications.
6. Metrics and acceptance gates. Clearly label thresholds as proposed rather
   than measured.
7. Ablation matrix and ordered experiment cards, each with hypothesis,
   controlled variables, measurements, sample size rationale, stop condition,
   artifact produced, and decision enabled.
8. Scoring rubric with declared weights and sensitivity analysis.
9. Smallest inexpensive experiments that reject weak candidates, followed by
   the real-device confirmation program for survivors.
10. Known unknowns, risks, and source appendix.

The winning result may be a routed portfolio rather than one model. Account for
the RAM, cold-start, fragmentation, and QA cost of extra engines and passes
before recommending specialization.
```

### Prompt 4 — runtime, conversion, licensing, and delivery feasibility

```text
You are a principal Android ML runtime, model-conversion, open-source licensing,
and release-engineering researcher. Identify the most relevant current model
families for Mindlayer's workloads, then determine whether each can actually be
shipped and operated in this Android stack. Produce an implementation-oriented
feasibility report, not a list of theoretically exportable architectures.

You have no repository access and no prior candidate report. The supplied
context below is sufficient to perform the task. Do not ask for project files
or assume unmentioned runtime behavior.

RESEARCH DATE AND EVIDENCE RULES

1. State your research date and use current primary sources: official model
   cards, repositories, releases, converter/runtime documentation, issue
   trackers, license texts, Android documentation, and Google Play rules.
2. Cite every version, artifact, compatibility, operation support, accelerator,
   license, and delivery claim with a direct link and source date.
3. Mark material claims VERIFIED, REPORTED, INFERRED, or UNKNOWN. A published
   conversion script without reproduced Android execution is not VERIFIED
   deployment support.
4. Distinguish:
   A — official, currently tested LiteRT/LiteRT-LM Android artifact;
   B — reproducible community Android artifact;
   C — documented conversion path with supported operations;
   D — bounded converter or runtime engineering;
   E — blocked or speculative.
5. Separate dependency resolution, converter success, desktop parity, Android
   CPU execution, accelerator execution, long-context stability, teardown, and
   same-process coexistence. Passing one does not prove the others.
6. Quote license language only briefly and link the controlling text. Flag
   matters requiring legal review rather than giving unsupported legal advice.

SUPPLIED PRODUCT AND STACK CONTEXT

Mindlayer is a privacy-first offline Android model service used by multiple
applications through a Kotlin SDK over AIDL, pipes, and Android SharedMemory.
Production has no network permission. The service owns model download,
integrity, consent, runtime lifecycle, accelerator fallback, memory/thermal
policy, and graceful degradation.

Models are delivered on demand through Google Play asset delivery, verified by
size and SHA-256, and materialized into app-private storage. Production builds
must use deterministic pinned artifacts and cannot load arbitrary untrusted
models. Atomic upgrade, rollback, pack removal, free-space checks, and
redistribution rights matter.

The supplied stack snapshot is dated 2026-09-04 and must be refreshed:

- Android min SDK 26, compile/target SDK 37, Java-targeted Android/Kotlin stack,
  principally arm64 with x86_64 emulator support.
- Gemma 4 E2B Instruct in a LiteRT-LM container via LiteRT-LM 0.16.1 for
  generative text, image, and audio.
- EmbeddingGemma 300M TFLite via LiteRT 2.2.0 CompiledModel, supporting 768
  native dimensions, 512/256/128 Matryoshka-style dimensions, and up to 2,048
  input tokens.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation TFLite
  models via LiteRT 2.2.0 CompiledModel.
- LiteRT-LM and base LiteRT coexist in one service process. A namespace
  workaround has been required historically for the paired artifacts.
- CPU, GPU, selected NPU/Google Tensor, Qualcomm, MediaTek, ABI, Android build,
  and driver differences matter.
- One generative model is selected at a time and at most one native
  conversation is kept warm. Backend/model changes or serious memory pressure
  may require process recreation because teardown/recreate has historically
  been unsafe.

The model portfolio must serve:

- OCR-first multilingual coffee-label normalization and evidence-grounded
  strict-JSON extraction, currently with an 8,192-token ceiling and optional
  image escalation;
- multilingual asymmetric meme retrieval, background indexing, interactive
  queries, up to five float32 768-dimensional vectors per meme, OCR, and hybrid
  FTS/vector search;
- sensitive receipt/ticket/import understanding with constrained labels or
  strict JSON, roughly 2,048 context, and 64–1,024 output-token budgets.

FIRST IDENTIFY CANDIDATES

Search current model ecosystems rather than assuming a fixed list. Select a
bounded shortlist for compact structured text, higher-quality structured text,
optional vision/audio, multilingual asymmetric embeddings, and OCR/document
understanding. Include the current Gemma, EmbeddingGemma, and PaddleOCR
baselines for comparison. Prefer candidates with a credible mobile advantage
and explicit relevance to the supplied tasks.

PRIMARY FEASIBILITY TASK

For each shortlisted model family, investigate the exact path to production
Android deployment in Mindlayer.

Trace source checkpoint -> tokenizer/chat template or preprocessing -> exporter
-> calibration/quantization -> LiteRT TFLite or LiteRT-LM `.litertlm` container
-> Kotlin Android API -> CPU/GPU/NPU execution -> Play on-demand delivery and
integrity pinning. Identify supported and unsupported ops, dynamic shapes,
custom kernels, multimodal encoders, tokenizer mismatches, structured-output
limitations, ABI/native libraries, minimum Android/runtime versions, and known
issues. Require source-versus-converted parity testing and exact hashes.

Investigate current LiteRT/LiteRT-LM compatibility and same-process behavior,
including namespace/SONAME collisions, linker namespaces, multiple
`CompiledModel` instances, accelerator coexistence, teardown/recreate, cache
contamination, and initialization order. Separate dependency resolution from
real runtime proof.

For every candidate, audit weights and tooling licenses, gated access,
redistribution, derivative/quantized artifact rights, commercial/Google Play
use, attribution, and update obligations. Assess pack size/limits, install and
free-space cost, split/reconstruction needs, atomic upgrades/removal, and
whether model materialization duplicates disk or memory unnecessarily.

Output a compatibility ladder with exact steps, blockers, proof still needed,
estimated engineering scope, rollback plan, and a clear production/experimental/
reject recommendation for each candidate. Cite only sources that directly
support the claim.

REQUIRED TECHNICAL AUDIT

For each candidate, trace and record:

- exact upstream checkpoint, revision/tag, files, hashes where published, and
  gated-download requirements;
- tokenizer, vocabulary, special tokens, prompt/chat template, normalization,
  image/audio preprocessing, pooling/task prefixes, and output post-processing;
- exporter name/version and supported source framework;
- calibration corpus and quantization recipe for weights, activations, KV
  cache, and embeddings where applicable;
- target LiteRT TFLite or LiteRT-LM container schema, supported operations,
  shapes, dynamic dimensions, custom kernels, signatures, metadata, and runtime
  API;
- source-to-export and export-to-Android parity tests, tolerances, golden
  inputs/outputs, structured-output tests, and quality-regression requirements;
- Android ABI/native dependencies, minimum SDK, CPU/GPU/NPU backend support,
  fallback behavior, thread/cache settings, and known device-specific issues;
- initialization, repeated inference, cancellation, close/recreate, backend
  switch, multi-engine coexistence, memory pressure, and process-restart tests;
- final artifact layout, deterministic build, manifest, hash pinning, Play pack
  design, download/install/free-space/temporary-space costs, atomic upgrade,
  rollback, and removal.

For licensing, examine the weights, source model, tokenizer/assets, exporter,
quantizer, runtime, preprocessing code, converted/quantized derivative,
benchmark/evaluation data, and any gated terms separately. Record commercial
use, Android/Google Play redistribution, derivative-artifact distribution,
attribution/notice, naming, acceptable-use, geographic/use restrictions,
source-code obligations, and termination/change risk. Mark the owner for every
required review or action.

REQUIRED REPORT

Return the report in this exact order:

1. Executive go/no-go summary and the recommended production, experimental,
   watchlist, and rejected candidates.
2. Candidate inventory with exact versions, reason for inclusion, intended
   capability profile, and feasibility class A–E.
3. Compatibility matrix covering artifact availability, conversion, ops,
   tokenizer/preprocessing, quantization, CPU/GPU/NPU, ABI/SDK, parity status,
   same-process risks, license, Play delivery, effort, and blocker.
4. One productionization dossier per candidate using the complete chain above.
   Provide exact documented commands/configuration only when supported by a
   cited current source; otherwise specify the experiment needed to discover
   them.
5. Same-process LiteRT/LiteRT-LM coexistence and teardown test plan, including
   initialization-order and backend permutations.
6. License and redistribution table with controlling links, obligations,
   unresolved questions, risk level, and required reviewer.
7. Model-pack/update design with disk-space accounting, temporary duplication,
   integrity manifest, atomic activation, rollback, and offline behavior.
8. Engineering estimates separated into converter work, runtime work, Android
   integration, quality validation, device validation, release/legal work, and
   ongoing maintenance.
9. Dependency-ordered proof gates and stop conditions.
10. Known unknowns and source appendix.

Do not recommend implementation merely because ONNX, TFLite, or another mobile
format exists. The release gate is a reproducible, rights-cleared artifact with
source parity, workload quality, Android real-device memory/latency, supported
accelerator fallback, same-process lifecycle evidence, integrity pinning, and
rollback.
```

### Prompt 5 — benchmark program and decision gates

```text
You are a principal ML benchmarking, Android performance, and release-gating
architect. Design a staged, executable benchmark and decision program for
selecting and safely shipping a Mindlayer model portfolio. The plan must test
quality, real RAM, latency, energy, compatibility, coexistence, and operational
risk on representative Android devices.

You have no repository access and no prior research reports. Use the complete
project snapshot below, identify current candidate families where necessary,
and produce a plan that another engineering team can execute. Do not ask for
code or invent measurements.

RESEARCH DATE AND EVIDENCE RULES

1. State your research date and browse current primary sources for Android
   memory/performance measurement, LiteRT/LiteRT-LM, model artifacts,
   accelerators, Google Play delivery, evaluation methods, and statistical
   practice.
2. Cite externally verifiable and version-sensitive claims directly with source
   dates. Label important statements VERIFIED, REPORTED, INFERRED, or UNKNOWN.
3. Clearly distinguish proposed thresholds from existing measured results.
4. Never treat emulator success, build success, converter success, CPU
   execution, or dependency resolution as real-device accelerator,
   coexistence, teardown, thermal, or release proof.
5. Do not fabricate commands or APIs. When current official tooling is
   insufficient, describe a bounded alternative measurement and its limits.

SUPPLIED SYSTEM CONTEXT

Mindlayer is a privacy-first offline Android inference service. Multiple apps
call a Kotlin SDK over AIDL, pipes, and Android SharedMemory. Models run in a
separate service process with no production network permission. The service
owns consent, on-demand Google Play model delivery, SHA-256 integrity,
inference lifecycle, accelerator fallback, memory/thermal behavior, and
graceful degradation.

The supplied 2026-09-04 stack snapshot, which must be refreshed, is:

- Android min SDK 26 and compile/target SDK 37; arm64 production devices and
  x86_64 emulator checks.
- Gemma 4 E2B Instruct through LiteRT-LM 0.16.1 for text/image/audio.
- EmbeddingGemma 300M through LiteRT 2.2.0, with 768/512/256/128 dimensions and
  up to 2,048 input tokens.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation models
  through LiteRT 2.2.0.
- All three inference stacks may share one service process. CPU, GPU, selected
  NPU/Google Tensor, Qualcomm, MediaTek, ABI, OS, and driver behavior vary.
- One generative model is selected at a time and one native conversation may
  remain warm. Model/backend changes and emergency memory recovery may require
  process recreation. Teardown/recreate and allocator/cache residue are known
  test concerns.

Historical—not current—x86_64 Android 16 CPU-emulator data for Gemma 4 E2B and
LiteRT-LM 0.12.0 reported roughly 127 MiB process baseline, 474 MiB fixed
engine/model PSS above baseline, and 9,208 bytes PSS per configured context
token. It sampled smaps_rollup and native/Android memory at 250 ms in a fresh
process per point. Use this only to design regression checks.

WORKLOADS TO REPRESENT

A. Coffee recognition: multilingual/noisy OCR normalization and
evidence-grounded strict-JSON extraction of coffee metadata, low hallucination,
user review, optional image escalation, potentially multiple text passes, and
an existing 8,192-token engine ceiling.

B. Meme search: multilingual asymmetric query/document embeddings,
interactive query latency, background batch indexing, OCR, hybrid FTS/vector
ranking, caches, up to five float32 768-dimensional vectors per item, dimension
and vector-quantization tradeoffs, and full reindex cost.

C. Financial documents: offline receipt/ticket/import understanding from OCR,
strict JSON or constrained labels, totals/currencies/dates/line items,
classification, multi-page and long OCR, roughly 2,048 context, and output
budgets around 64–1,024 tokens.

PRIMARY TASK

Design a staged benchmark and decision program for selecting and safely
shipping the model portfolio for these workloads.

Define reproducible artifact manifests, device classes, runtime/backend matrix,
memory instrumentation, latency/thermal/energy tests, conversion parity checks,
workload-native corpora, slice metrics, statistical treatment, and report
schema. Include exact identities for model, hash, quantization, runtime,
backend, device/SoC, Android/driver, prompt/schema, validator, and corpus in
every result. Require fresh-process measurements plus realistic service reuse,
three-stack coexistence, teardown/recreate, pressure, cancellation, and failure
testing.

Create dependency-ordered gates that cheaply reject bad models before expensive
testing: license/artifact gate, converter/CPU smoke, parity, structured-output or
embedding sanity, small corpus, RAM/context sweep, real-device accelerator,
coexistence, full corpus, sustained thermal/battery, staged rollout. Each gate
must have acceptance criteria, stop conditions, evidence produced, and rollback
conditions.

Output executable experiment cards, a results-table schema, suggested device
coverage, proposed—not fabricated—quality/RAM/latency thresholds, and a final
decision template that supports production, experimental, watchlist, and reject
states. Keep emulator, build, artifact, and real-device evidence boundaries
explicit.

CANDIDATE AND ARTIFACT SCOPE

The program must support the current baseline plus newly researched candidates
for compact structured text, higher-quality text, optional vision/audio,
multilingual embeddings, and OCR. Every benchmark row must uniquely identify:

- source model and exact revision;
- converted artifact hash, format, quantization and calibration recipe;
- tokenizer, prompt/chat template, task prefix, schema and validator version;
- runtime/library build, ABI, backend and all material runtime flags;
- device model, RAM class, SoC, Android build, driver and thermal conditions;
- corpus version, slice, sample identity policy, repetition, and random seed.

QUALITY PROGRAM

Define held-out, versioned corpora and workload-native metrics:

- coffee: per-field exact/normalized precision and recall, abstention,
  unsupported values, evidence validity, JSON validity, field confusions,
  multilingual/noisy-OCR slices, user correction burden, and incremental value
  of vision and multi-pass processing;
- memes: nDCG@k, MRR, Recall@k, bad-top-result and zero-result rates,
  multilingual/slang/emoji/template/intent slices, query latency, indexing
  throughput/energy, storage per item, index working set, and ablations for
  dimension, vector storage, semantic slots, fusion, cache, and ANN method;
- documents: normalized vendor/total/currency/date accuracy, line-item
  precision/recall and arithmetic consistency, kind/category macro-F1, ticket
  and transaction-split accuracy, schema/JSON validity, abstention,
  hallucinations, multi-page/long-input behavior, and correction burden.

MEMORY, PERFORMANCE, AND RELIABILITY PROGRAM

Measure fresh-process baseline, model load steady state and peak, first and warm
inference peaks, post-request state, post-close residue, and repeated
load/run/close cycles. Track PSS/RSS and smaps categories, Java/native heap,
mapped files, graphics/dmabuf/GPU or bounded equivalents, NPU/shared buffers
where observable, app-plus-service total, thread/file-descriptor counts,
SwapPss, LMK/kill evidence, failures, and backend fallbacks.

Sweep configured context, actual prompt and output length, modality, image/audio
size, embedding sequence/dimension/batch, OCR image/line size, backend,
initialization order, sequential versus concurrent inference, cancellation,
pressure, and model switching. Measure cold/warm latency, time to first token,
prefill/decode throughput, task latency, sustained thermal throttling and energy
where defensible. Specify warm-up, quiescence, sampling frequency, repetitions,
confidence intervals, outlier handling, and stop conditions.

Cover representative 4, 6, 8, 12, and 16+ GB devices across older and current
Qualcomm, MediaTek, and Google Tensor SoCs. State which physical devices are
essential, which can be rented or substituted, and what an emulator can and
cannot prove.

REQUIRED STAGED GATES

At minimum define:

G0 evidence and license triage;
G1 reproducible source/artifact manifest;
G2 conversion plus desktop/source parity;
G3 Android CPU smoke and deterministic-output sanity;
G4 small-corpus quality and early rejection;
G5 fresh-process RAM/context sweep;
G6 real-device GPU/NPU support and fallback;
G7 generative/embedding/OCR coexistence and initialization order;
G8 teardown, repeated lifecycle, cancellation and memory pressure;
G9 full workload corpus and ablations;
G10 sustained thermal, energy, background/foreground interaction and LMK;
G11 deterministic Play delivery, integrity, upgrade and rollback;
G12 staged field rollout with privacy-safe observability.

For every gate give purpose, prerequisites, exact experiment, controlled
variables, instrumentation, required devices, metrics, proposed acceptance
criteria, evidence artifact, owner skill set, failure triage, stop condition,
and rollback or disposition. Order gates so cheap decisive failures occur early.

REQUIRED REPORT

Return the report in this exact order:

1. One-page benchmark strategy and critical decisions.
2. Assumption/evidence ledger identifying what is current, stale, or unknown.
3. Candidate/artifact manifest schema and example populated for the supplied
   baseline only; label unmeasured cells UNKNOWN.
4. Corpus and annotation plans for all three workloads.
5. Device/backend matrix with rationale and coverage limits.
6. Instrumentation and reproducibility protocol.
7. Full G0–G12 gate table.
8. Numbered executable experiment cards with hypotheses, steps, inputs,
   measurements, analysis, acceptance/stop criteria, outputs, and dependencies.
9. Results-table/database schema and comparison rules.
10. Proposed decision scorecards with sensitivity analysis and explicit
    production, experimental, watchlist, and reject outcomes.
11. Schedule ordered by dependencies, resource/role needs, and parallelizable
    work—not calendar promises unsupported by team data.
12. Risks, known unknowns, and source appendix.

The final program must make it impossible to confuse missing data with a zero,
estimated RAM with measured RAM, or a passing isolated model with a shippable
multi-runtime Android portfolio.
```

### Prompt 6 — final synthesis and adversarial review

This prompt can synthesize earlier reports or run independently. If reports are
available, paste them at the marked location before submitting it.

```text
You are the principal decision reviewer for an offline Android model portfolio.
Produce a final, evidence-backed recommendation for Mindlayer and its three
consumer workloads. If focused research reports are appended, audit and
synthesize them. If none are appended, perform the missing live research
yourself rather than stopping or fabricating report content.

You have no repository access. Treat the project snapshot below as the complete
supplied product context. Do not request source files or invent implementation
details.

RESEARCH DATE AND EVIDENCE RULES

1. State the date of review and verify all time-sensitive facts on the live web.
2. Follow report citations to primary sources. Independently verify decisive
   claims using official model cards, papers, repositories, releases,
   runtime/converter documentation, issue trackers, licenses, and Android/Play
   documentation.
3. Cite external claims near the claim with direct links and dates. Mark each
   material claim VERIFIED, REPORTED, INFERRED, or UNKNOWN.
4. Resolve contradictions explicitly. Do not average incompatible measurements,
   compare mismatched quantizations or prompts as if controlled, or reward a
   candidate for missing data.
5. Keep download/disk size, parameter count, active parameters, mapped weights,
   peak Android RAM, KV/activation memory, GPU/NPU memory, vector/index memory,
   latency, energy, and quality separate.
6. Existing artifact, converter success, Android CPU execution, accelerator
   support, same-process coexistence, teardown safety, and release clearance are
   separate gates.

SUPPLIED PRODUCT CONTEXT

Mindlayer is a privacy-first offline Android inference service. Several client
applications use a Kotlin SDK over AIDL, pipes, and Android SharedMemory. The
models run in a separate service process without production network
permission. Mindlayer owns consent, on-demand Google Play model delivery,
size/SHA-256 verification, inference lifecycle, CPU/GPU/NPU fallback, memory
pressure, thermal policy, and graceful degradation.

The supplied 2026-09-04 stack snapshot must be refreshed:

- Android min SDK 26, compile/target SDK 37, principally arm64.
- Gemma 4 E2B Instruct in a LiteRT-LM container via LiteRT-LM 0.16.1 for
  generative text, image, and audio.
- EmbeddingGemma 300M via base LiteRT 2.2.0, supporting asymmetric query/
  document tasks, 768 native dimensions, 512/256/128 Matryoshka-style
  dimensions, and up to 2,048 input tokens.
- PaddleOCR PP-OCRv5 mobile detection, recognition, and orientation models via
  base LiteRT 2.2.0.
- Generative, embedding, and three OCR models may coexist in one service
  process, including native libraries, delegates, GPU/NPU resources, thread
  pools, caches, mappings, and allocators.
- CPU, GPU, selected NPU/Google Tensor, Qualcomm, MediaTek, Android/driver, ABI,
  and real-device differences matter. Emulator evidence is bounded.
- One generative model is selected at a time and at most one native
  conversation remains warm. Backend/model changes or emergency pressure may
  require process recreation because native teardown/recreate has historically
  been unsafe or incomplete.
- Offline operation, deterministic conversion, pinned hashes, Play delivery,
  compatible redistribution rights, integrity, fallback, and rollback are hard
  constraints.

Historical—not current—x86_64 Android 16 CPU-emulator measurements with Gemma 4
E2B and LiteRT-LM 0.12.0 reported about 127 MiB process baseline, 474 MiB fixed
engine/model PSS above baseline, and 9,208 bytes PSS per configured context
token. Treat them only as a regression hypothesis and measurement-method clue.

CONSUMER WORKLOADS

A. Coffee bags: interactive OCR-first multilingual/noisy-text normalization and
evidence-grounded strict-JSON extraction of roaster, coffee name, origin,
producer/farm/region, varieties, process, tasting notes, dates, weight,
altitude, and decaf status. Correct abstention and low correction burden matter.
Current processing may use multiple text passes, an 8,192-token ceiling, and
optional image escalation.

B. Meme retrieval: multilingual asymmetric semantic search over OCR text,
titles, descriptions, search phrases, template, content, intent, emotion,
emoji, and differentiator. The baseline stores up to five float32
768-dimensional vectors per meme, runs background indexing and interactive
queries, caches embeddings, and fuses FTS with vector results. Dimension,
quantized vectors, slot count, index working set, model RAM, and full reindex
cost are separate concerns.

C. Financial documents: offline receipt/ticket/import understanding from
Tesseract OCR and ML Kit language/translation/barcode inputs. Tasks include
document kind, vendor/total/currency/date, line items, ticket fields,
multi-page inputs, transaction splitting, expense category, short titles, and
import-schema detection/validation/refinement. Outputs are constrained labels
or strict JSON, with roughly 2,048 context and 64–1,024 output-token budgets.

PRIMARY REVIEW TASK

Synthesize the model-landscape, RAM, workload-fit, compatibility, and benchmark
evidence into a robust portfolio decision. Re-open primary sources for decisive
claims and identify missing experiments rather than converting uncertainty into
false precision.

Produce:

1. a recommended production portfolio now, experimental candidates, watchlist,
   and rejects;
2. capability/profile routing for compact text extraction, quality text,
   optional vision/audio, embeddings, and OCR;
3. per-workload scores with weights and sensitivity analysis;
4. total portfolio RAM/storage/download/startup/reindex/QA cost;
5. a ranked memory-optimization roadmap;
6. the exact experiments still required before implementation or release;
7. explicit failure, fallback, rollback, and observability requirements.

Run an adversarial challenge pass against the preferred answer. Look for stale
versions, benchmark contamination, model-card-only claims, incompatible
quantization comparisons, hidden KV/vision/GPU memory, unsupported converter
ops, license barriers, poor multilingual or abstention behavior, reindex cost,
co-resident runtime hazards, and model-switch cold-start/fragmentation. If the
winner changes under reasonable scoring weights or unverified assumptions,
say that the decision is not yet robust.

REQUIRED ANALYSIS

Evaluate at least these portfolio capabilities independently:

- FAST_TEXT for compact classification and structured extraction;
- QUALITY_TEXT for difficult multilingual/noisy structured extraction;
- optional VISION/AUDIO only where incremental product value is demonstrated;
- EMBEDDING for asymmetric multilingual retrieval;
- OCR/document understanding.

For every shortlisted candidate classify deployment status:
A official tested LiteRT/LiteRT-LM Android artifact;
B reproducible community Android artifact;
C documented conversion with supported operations;
D bounded converter/runtime engineering;
E blocked or speculative.

Require workload-native quality rather than generic leaderboards. Coffee metrics
must cover per-field accuracy, abstention, evidence, JSON validity, field
confusion, noisy/multilingual OCR, correction burden, and vision ablation. Meme
metrics must cover nDCG/MRR/Recall, multilingual/slang/emoji/intent slices,
query/indexing performance, dimension/vector/slot/index ablations, storage,
working-set RAM, and reindex cost. Financial-document metrics must cover exact
normalized fields, line items, arithmetic consistency, kind/category, schema/
JSON validity, abstention, long/multi-page inputs, and correction burden.

Account for complete portfolio cost: downloads, installed and temporary disk,
mapped/model/KV/activation/GPU/NPU RAM, vector/index memory, media and IPC
buffers, cold/warm startup, switching, fragmentation, background overlap,
thermal/energy behavior, reindexing, QA matrix, license obligations, artifact
updates, fallback, and operational complexity.

REQUIRED REPORT

Return the final report in this exact order:

1. One-page decision memo using NOW, NEXT EXPERIMENT, LATER, and REJECT/DEFER.
2. Executive portfolio recommendation: production now, experiments, watchlist,
   and rejects. Say explicitly if the supplied baseline should remain.
3. Workload-to-profile-to-model routing table with fallback/degradation.
4. Candidate matrix with exact versions, architecture, modality, context,
   quantization/artifact, RAM evidence, runtime/accelerator status, feasibility
   class, license, and blocker.
5. Per-workload quality scorecards with declared weights and sensitivity
   analysis.
6. Total portfolio resource/cost table.
7. Compatibility, conversion, same-process coexistence, delivery, licensing,
   upgrade, and rollback assessment.
8. Ranked memory optimization roadmap showing affected memory component,
   expected benefit and evidence, quality/latency cost, runtime dependency,
   effort, risk, validation, exit criterion, and rollback.
9. Dependency-ordered experiments still required before implementation,
   experimental rollout, and production promotion. Include acceptance and stop
   conditions.
10. Adversarial review findings and whether the choice remains robust.
11. Known unknowns and source appendix.

ADVERSARIAL CHALLENGE

Before finalizing, try to disprove the preferred answer. Look for stale versions,
model-card-only claims, benchmark leakage, prompt or quantization mismatches,
unobserved KV/vision/GPU/NPU memory, unsupported converter operations,
tokenizer/template drift, license barriers, weak multilingual or abstention
behavior, reindex costs, background/foreground overlap, co-resident runtime
hazards, teardown residue, process-restart effects, and model-switch cold-start
or fragmentation. If reasonable score weights or any unverified assumption can
change the winner, state that the decision is not yet robust and name the
decisive experiment.

Do not recommend production promotion without a reproducible rights-cleared
artifact, conversion parity, workload-native quality gate, real-device
memory/latency result, supported backend fallback, same-process lifecycle
result, integrity pin, observable failure path, and rollback.

FOCUSED REPORTS

Optional: paste prior model-landscape, RAM, workload-fit, compatibility, or
benchmark reports below this line. If this section remains empty, conduct the
necessary research directly.

[PASTE OPTIONAL REPORTS HERE]
```

## Suggested execution order

1. Copy and paste the complete contents of any one fenced block. No shared
   context or text from another section is required.
2. For parallel research, run focused prompts 1–5 independently so one
   researcher's assumptions do not anchor the others.
3. Paste those reports into focused prompt 6 for final synthesis. Prompt 6 can
   also run without attachments and will perform missing research itself.
4. Use the master prompt instead when one researcher should own the complete
   investigation end to end.

## What a useful answer must not do

- Recommend one model for every workload without comparing a portfolio.
- Rank models from generic chat benchmarks alone.
- Derive RAM from parameter count or file size alone.
- Present emulator CPU results as real-device GPU/NPU proof.
- Treat dependency resolution or successful export as Android runtime proof.
- Ignore tokenizer, chat template, quantization recipe, artifact hash, or model
  version when comparing outputs.
- Hide unknowns behind precise estimates.
- Recommend production deployment without license and redistribution review.
- Ignore model switching, three-runtime coexistence, teardown, or process
  lifetime.
- Optimize model RAM while overlooking vector indexes, bitmaps, IPC copies,
  OCR tensors, or the foreground consumer app.

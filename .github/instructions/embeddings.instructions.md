---
applyTo: |
  app/src/main/kotlin/com/adsamcik/mindlayer/service/engine/Embedding*.kt
  app/src/test/kotlin/com/adsamcik/mindlayer/service/engine/Embedding*.kt
  sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/Embedding*.kt
  sdk/src/main/kotlin/com/adsamcik/mindlayer/sdk/InMemoryVectorIndex.kt
  shared/src/main/kotlin/com/adsamcik/mindlayer/Embedding*.kt
  shared/src/main/kotlin/com/adsamcik/mindlayer/VectorBlobHandle.kt
  gemma_embed_model/**
---

## Threat model

Never log vectors or text inputs. Use `safeLabel()` on backend errors. Vectors are sensitive because inversion attacks can recover source text.

## Capability gating

Every new embedding method must capability-check `FEATURE_EMBEDDINGS`; old services degrade to `NOT_SUPPORTED`.

## AIDL discipline

Mirror AIDL byte-identically between `app/` and `sdk/`. Methods are append-only.

## SHM transport

Inline batch uses real `SharedMemory`; deferred uses file-backed blobs under `cacheDir/embedding-blobs/<uid>/` for process-death survival.

## Tokenizer

SentencePiece is bundled in the same AI Pack as the `.tflite`; model and tokenizer SHA-256 are both verified at discovery.

## Backend chain

Use NPU→GPU→CPU matching chat.

## Memory pressure

Unload the embedding model before chat sessions under CRITICAL/EMERGENCY pressure.

## FGS

The service stays foregrounded while `activeEmbeddingBatches + activeInferences > 0`.

## Rate-limit cost weights

`embed=1.0`, `embedBatch=0.5*size`, `embedBatchShm=same`, `embedBatchDeferred=0.5`, fetch/cancel/ack/cancelEmbed=0.1.

## InMemoryVectorIndex

Pure-Kotlin client-process helper; not a vector store; no persistence; scope <10K vectors.

## DeferredStore migration

`kind` distinguishes chat vs embedding; `blob_path`/`blob_bytes`/`per_item_metadata_json` carry embedding payloads; `blob_bytes` counts against per-UID byte quota.


package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.EmbeddingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests for the embedding-side of [IpcInputValidator] — added in
 * the PR1 hardening pass that gates the embedding AIDL surface at the
 * binder boundary. All these checks must fire BEFORE the coordinator and
 * engine see the request; otherwise the engine has to defensively re-check
 * everything which it would do anyway, but at higher cost (mutex acquired,
 * tokenizer hit, etc.).
 */
class IpcInputValidatorEmbeddingTest {

    // ── validateEmbeddingRequest ─────────────────────────────────────────

    @Test fun `accepts a well-formed minimal request`() {
        IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = "hello"))
    }

    @Test fun `rejects unsupported schemaVersion`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(schemaVersion = 99, text = "x"),
            )
        }
        assertTrue(msg, msg.contains("schemaVersion=99"))
    }

    @Test fun `rejects zero schemaVersion`() {
        expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(schemaVersion = 0, text = "x"),
            )
        }
    }

    @Test fun `rejects text exceeding the byte budget`() {
        // 512 KiB + 1 byte
        val tooBig = "a".repeat((IpcInputValidator.MAX_EMBEDDING_INPUT_BYTES + 1).toInt())
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = tooBig))
        }
        assertTrue(msg, msg.contains("text too long"))
    }

    @Test fun `accepts text right at the byte budget`() {
        val ok = "a".repeat(IpcInputValidator.MAX_EMBEDDING_INPUT_BYTES.toInt())
        IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = ok))
    }

    @Test fun `rejects oversize tag`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(text = "x", tag = "t".repeat(IpcInputValidator.MAX_EMBEDDING_TAG_LEN + 1)),
            )
        }
        assertTrue(msg, msg.contains("tag too long"))
    }

    @Test fun `rejects empty modelId when supplied`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(text = "x", modelId = ""),
            )
        }
        assertTrue(msg, msg.contains("modelId must not be empty"))
    }

    @Test fun `rejects oversize modelId`() {
        expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(
                    text = "x",
                    modelId = "m".repeat(IpcInputValidator.MAX_EMBEDDING_MODEL_ID_LEN + 1),
                ),
            )
        }
    }

    @Test fun `rejects out-of-range taskType`() {
        for (bad in listOf(-1, 8, 99, Int.MAX_VALUE)) {
            val msg = expectFailure {
                IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = "x", taskType = bad))
            }
            assertTrue(msg, msg.contains("taskType=$bad"))
        }
    }

    @Test fun `accepts all valid taskType values`() {
        for (good in 0..7) {
            IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = "x", taskType = good))
        }
    }

    @Test fun `rejects zero or negative outputDim`() {
        for (bad in listOf(0, -1, -100)) {
            expectFailure {
                IpcInputValidator.validateEmbeddingRequest(EmbeddingRequest(text = "x", outputDim = bad))
            }
        }
    }

    @Test fun `rejects absurd outputDim`() {
        expectFailure {
            IpcInputValidator.validateEmbeddingRequest(
                EmbeddingRequest(
                    text = "x",
                    outputDim = IpcInputValidator.MAX_EMBEDDING_OUTPUT_DIM + 1,
                ),
            )
        }
    }

    // ── validateEmbeddingRequests (batch) ────────────────────────────────

    @Test fun `batch rejects empty list`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequests(emptyList(), maxBatchSize = 64)
        }
        assertTrue(msg, msg.contains("must not be empty"))
    }

    @Test fun `batch rejects size over cap`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequests(
                List(5) { EmbeddingRequest(text = "x") },
                maxBatchSize = 4,
            )
        }
        assertTrue(msg, msg.contains("too large"))
    }

    @Test fun `batch fails fast on per-item error`() {
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequests(
                listOf(
                    EmbeddingRequest(text = "ok"),
                    EmbeddingRequest(text = "x", taskType = 99),
                ),
                maxBatchSize = 64,
            )
        }
        assertTrue(msg, msg.contains("taskType"))
    }

    @Test fun `batch rejects aggregate byte budget overflow`() {
        // Each item is just under per-item cap; aggregating 3 of them
        // exceeds the aggregate cap.
        val text = "a".repeat((IpcInputValidator.MAX_EMBEDDING_INPUT_BYTES / 2).toInt())
        val msg = expectFailure {
            IpcInputValidator.validateEmbeddingRequests(
                List(3) { EmbeddingRequest(text = text) },
                maxBatchSize = 64,
            )
        }
        assertTrue(msg, msg.contains("aggregate"))
    }

    @Test fun `batch accepts well-formed inputs at the cap`() {
        IpcInputValidator.validateEmbeddingRequests(
            List(64) { EmbeddingRequest(text = "x", taskType = EmbeddingTask.RETRIEVAL_DOCUMENT) },
            maxBatchSize = 64,
        )
    }

    private inline fun expectFailure(block: () -> Unit): String {
        try {
            block()
            fail("expected IllegalArgumentException")
            error("unreachable")
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
    }
}

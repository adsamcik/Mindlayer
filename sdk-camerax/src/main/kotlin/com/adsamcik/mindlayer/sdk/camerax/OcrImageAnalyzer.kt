package com.adsamcik.mindlayer.sdk.camerax

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.sdk.OcrSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX [ImageAnalysis.Analyzer] that:
 *
 *  1. Converts each incoming [ImageProxy] to an [OcrFrame] (Y-plane
 *     copy, rotation rounded to {0,90,180,270}).
 *  2. Runs client-side [OcrFramePresort] scoring; drops bad-quality
 *     frames before they cross the binder boundary.
 *  3. Pushes the accepted frame's metadata to the active [OcrSession].
 *  4. Closes the [ImageProxy] **immediately** (CameraX requires the
 *     analyzer to dispose its input before the next frame is delivered).
 *
 * # Usage
 *
 * ```kotlin
 * val session = mindlayer.ocrSession(OcrProfile.Receipt) { maxFrames = 30 }
 * val analyzer = OcrImageAnalyzer(session) { frame, ack ->
 *     // Optional: observe per-frame ACKs for UI throttling feedback.
 * }
 * val analysis = ImageAnalysis.Builder()
 *     .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
 *     .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
 *     .build()
 *     .also { it.setAnalyzer(executor, analyzer) }
 * ```
 *
 * # Thread safety
 *
 * CameraX guarantees ``analyze()`` is called sequentially per-analyzer
 * on a single executor — the implementation does NOT lock and relies
 * on that guarantee. Concurrent pushes to the same session from
 * multiple analyzers are unsupported (one analyzer per session).
 *
 * # Backpressure
 *
 * Use ``ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`` so CameraX drops
 * unanalysed frames rather than queueing — combined with the
 * presort's duplicate-detection this avoids redundant work when the
 * user holds the phone still over a target.
 *
 * @property session the live OCR session frames are pushed to.
 * @property scope coroutine scope for pushing frames; defaults to a
 *   single-threaded supervisor on [Dispatchers.IO]. Cancelling the
 *   scope stops further binder calls.
 * @property runClientSidePresort when false, every frame is pushed
 *   regardless of quality (the service-side presort still gates).
 *   When true (default), bad frames are dropped client-side.
 * @property onAck optional callback fired for each binder ACK so the
 *   caller can throttle / surface UI feedback. Runs on the analyzer
 *   thread, **not** the UI thread — dispatch yourself if needed.
 */
class OcrImageAnalyzer(
    private val session: OcrSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val runClientSidePresort: Boolean = true,
    private val onAck: ((OcrFrame, OcrFrameAck) -> Unit)? = null,
) : ImageAnalysis.Analyzer {

    private val frameIdSequence = AtomicLong(0L)
    @Volatile private var previousDHash: ULong? = null
    @Volatile private var droppedCount: Long = 0L

    /** Cumulative count of frames the client-side presort dropped. */
    val clientDroppedCount: Long get() = droppedCount

    /**
     * Reset the presort state — call after [com.adsamcik.mindlayer.sdk.OcrSession.finalize]
     * when re-opening a new session with the same analyzer instance.
     */
    fun resetPresortState() {
        previousDHash = null
        droppedCount = 0L
        frameIdSequence.set(0L)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val frameId = frameIdSequence.incrementAndGet()
            val ocrFrame = OcrFrame.fromImageProxy(image, frameId)
            val frameWithHint = if (runClientSidePresort) {
                val score = OcrFramePresort.score(
                    yPlane = ocrFrame.bytes,
                    width = ocrFrame.width,
                    height = ocrFrame.height,
                    previousDHash = previousDHash,
                )
                if (!score.isGood) {
                    droppedCount++
                    return
                }
                previousDHash = score.dHash
                ocrFrame.copy(qualityHint = score.hint)
            } else {
                ocrFrame
            }
            scope.launch {
                try {
                    val ack = session.pushFrame(
                        meta = frameWithHint.toFrameMeta(),
                        yPlane = frameWithHint.bytes,
                        width = frameWithHint.width,
                        height = frameWithHint.height,
                    )
                    onAck?.invoke(frameWithHint, ack)
                } catch (_: Throwable) {
                    droppedCount++
                    onAck?.invoke(
                        frameWithHint,
                        OcrFrameAck(
                            frameId = frameWithHint.frameId,
                            status = OcrFrameAck.STATUS_DROPPED_BUSY,
                            queueDepth = 0,
                            retryAfterMs = 0L,
                        ),
                    )
                }
            }
        } finally {
            // CameraX contract: analyzer MUST close the ImageProxy
            // before returning, or the pipeline stalls.
            image.close()
        }
    }
}

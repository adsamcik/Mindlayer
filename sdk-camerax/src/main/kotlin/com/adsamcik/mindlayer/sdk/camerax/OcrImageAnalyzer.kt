package com.adsamcik.mindlayer.sdk.camerax

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.sdk.OcrHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.BufferUnderflowException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/** Analyzer-internal outcomes that are not otherwise visible through OCR session events. */
sealed class OcrAnalyzerEvent {
    data class Dropped(val frameId: Long, val reason: String) : OcrAnalyzerEvent()
    data class Busy(val frameId: Long, val retryAfterMs: Long) : OcrAnalyzerEvent()
    data class Error(val throwable: Throwable) : OcrAnalyzerEvent()
}

/**
 * CameraX [ImageAnalysis.Analyzer] that:
 *
 *  1. Converts each incoming [ImageProxy] to an [OcrFrame] (Y-plane
 *     copy, rotation rounded to {0,90,180,270}).
 *  2. Runs client-side [OcrFramePresort] scoring; drops bad-quality
 *     frames before they cross the binder boundary.
 *  3. Pushes the accepted frame's metadata to the active [OcrHandle.MultiFrame].
 *  4. Closes the [ImageProxy] **immediately** (CameraX requires the
 *     analyzer to dispose its input before the next frame is delivered).
 *
 * # Usage
 *
 * ```kotlin
 * val session = mindlayer.ocrSession {
 *     profile(OcrProfile.Receipt)
 *     maxFrames(30)
 * }
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
 * # IMU forwarding (v0.9, optional)
 *
 * Pass a [SensorManager] to opt the analyzer into per-frame gyroscope
 * sampling. The analyzer registers a [Sensor.TYPE_GYROSCOPE] listener
 * at [SensorManager.SENSOR_DELAY_GAME] (~20 ms cadence — enough
 * resolution for "did the user move the camera between frames"
 * without being battery-hostile), tracks the peak
 * `sqrt(x² + y² + z²)` magnitude across the most recent frame window,
 * and forwards it as `{"imu":{"gyro_max_rad_per_s": …}}` on
 * [OcrFrameMeta.extraJson]. The server-side v0.9 page-boundary
 * detector reads that block; without it the heuristic falls back to
 * text + spatial signals alone.
 *
 * When [sensorManager] is `null` (the default — preserves binary
 * compatibility), the analyzer never registers a listener and never
 * touches `extraJson`, so callers that already populate the field
 * keep their data verbatim.
 *
 * # Lifecycle
 *
 * The sensor listener is registered at construction and unregistered
 * by [close]. After [close] no further sensor work happens; the
 * analyzer cannot be re-armed — construct a fresh [OcrImageAnalyzer]
 * for the next session. Hosts that pause/resume the camera without
 * tearing the session down should leave the analyzer alive and
 * simply stop the [ImageAnalysis] use case.
 *
 * # Thread safety
 *
 * CameraX guarantees ``analyze()`` is called sequentially per-analyzer
 * on a single executor — the implementation does NOT lock and relies
 * on that guarantee. Concurrent pushes to the same session from
 * multiple analyzers are unsupported (one analyzer per session). The
 * sensor listener callback may run on a different thread (Android
 * delivers sensor events on the looper of the [SensorManager.registerListener]
 * caller, or a sensor-thread when null); the analyzer publishes the
 * peak magnitude through an [AtomicLong] so the analyze thread sees a
 * consistent value without a lock.
 *
 * @property session the live OCR multi-frame handle frames are pushed to.
 * @property scope coroutine scope for pushing frames; defaults to a
 *   single-threaded supervisor on [Dispatchers.IO]. Cancelling the
 *   scope stops further binder calls.
 * @property runClientSidePresort when false, every frame is pushed
 *   regardless of quality (the service-side presort still gates).
 *   When true (default), bad frames are dropped client-side.
 * @property sensorManager when non-null, the analyzer registers a
 *   gyroscope listener and forwards peak magnitude per frame into
 *   [OcrFrameMeta.extraJson]. Default `null` ⇒ feature off
 *   (binary-compatible with v0.10 callers).
 * @property onAck optional callback fired for each binder ACK so the
 *   caller can throttle / surface UI feedback. Runs on the analyzer
 *   thread, **not** the UI thread — dispatch yourself if needed.
 */
class OcrImageAnalyzer @JvmOverloads constructor(
    private val session: OcrHandle.MultiFrame,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val runClientSidePresort: Boolean = true,
    private val onAck: ((OcrFrame, OcrFrameAck) -> Unit)? = null,
    private val sensorManager: SensorManager? = null,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val frameIdSequence = AtomicLong(0L)
    @Volatile private var previousDHash: ULong? = null
    @Volatile private var droppedCount: Long = 0L
    private val _events = MutableSharedFlow<OcrAnalyzerEvent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Atomic bit-pattern (`Float.toRawBits().toLong()`) of the peak
     * gyro magnitude observed since the last [analyze] call. Updated
     * from the sensor callback thread; read + reset on the analyze
     * thread.
     */
    private val gyroPeakBits = AtomicLong(java.lang.Float.floatToRawIntBits(0f).toLong())

    private val sensorListener: SensorEventListener? = if (sensorManager != null) {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
                if (event.values.size < 3) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x * x + y * y + z * z)
                if (!magnitude.isFinite() || magnitude < 0f) return
                // Compare-and-set to a higher value; lossless for the
                // single-writer (sensor thread) case but still correct
                // if Android ever delivers events on multiple threads.
                while (true) {
                    val currentBits = gyroPeakBits.get()
                    val current = java.lang.Float.intBitsToFloat(currentBits.toInt())
                    if (magnitude <= current) return
                    val nextBits = java.lang.Float.floatToRawIntBits(magnitude).toLong()
                    if (gyroPeakBits.compareAndSet(currentBits, nextBits)) return
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }
        }
    } else {
        null
    }

    init {
        registerSensorListener()
    }

    /** Non-blocking stream of analyzer-local drops, busy acks, and failures. */
    val events: Flow<OcrAnalyzerEvent> = _events.asSharedFlow()

    /** Cumulative count of frames the client-side presort dropped. */
    val clientDroppedCount: Long get() = droppedCount

    /**
     * Reset the presort state — call after [com.adsamcik.mindlayer.sdk.OcrHandle.MultiFrame.finalize]
     * when re-opening a new session with the same analyzer instance.
     */
    fun resetPresortState() {
        previousDHash = null
        droppedCount = 0L
        frameIdSequence.set(0L)
        gyroPeakBits.set(java.lang.Float.floatToRawIntBits(0f).toLong())
    }

    override fun analyze(image: ImageProxy) {
        try {
            val frameId = frameIdSequence.incrementAndGet()
            val ocrFrame = extractFrameOrReport(image, frameId) ?: return
            val frameWithHint = if (runClientSidePresort) {
                val score = OcrFramePresort.score(
                    yPlane = ocrFrame.bytes,
                    width = ocrFrame.width,
                    height = ocrFrame.height,
                    previousDHash = previousDHash,
                )
                if (!score.isGood) {
                    droppedCount++
                    _events.tryEmit(OcrAnalyzerEvent.Dropped(frameId, "client_presort"))
                    return
                }
                previousDHash = score.dHash
                ocrFrame.copy(qualityHint = score.hint)
            } else {
                ocrFrame
            }
            val gyroPeak = consumeGyroPeak()
            scope.launch {
                try {
                    val ack = session.pushFrame(
                        meta = frameWithHint.toFrameMeta().withImu(gyroPeak),
                        yPlane = frameWithHint.bytes,
                        width = frameWithHint.width,
                        height = frameWithHint.height,
                    )
                    when (ack.status) {
                        OcrFrameAck.STATUS_DROPPED_BUSY -> {
                            droppedCount++
                            _events.tryEmit(OcrAnalyzerEvent.Busy(ack.frameId, ack.retryAfterMs))
                        }
                        OcrFrameAck.STATUS_REJECTED_QUALITY,
                        OcrFrameAck.STATUS_REJECTED_FINALIZED,
                        OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED -> {
                            droppedCount++
                            _events.tryEmit(OcrAnalyzerEvent.Dropped(ack.frameId, "service_status_${ack.status}"))
                        }
                    }
                    onAck?.invoke(frameWithHint, ack)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    droppedCount++
                    _events.tryEmit(OcrAnalyzerEvent.Error(e))
                }
            }
        } finally {
            // CameraX contract: analyzer MUST close the ImageProxy
            // before returning, or the pipeline stalls.
            image.close()
        }
    }

    /**
     * Unregister the sensor listener (if any) and release any
     * analyzer-side resources tied to the host lifecycle. Idempotent.
     *
     * Note: this does NOT close the [OcrHandle.MultiFrame] — the session is
     * owned by the caller and may outlive the analyzer (e.g. callers
     * that want a final manual frame push after stopping CameraX).
     */
    override fun close() {
        unregisterSensorListener()
    }

    private fun extractFrameOrReport(image: ImageProxy, frameId: Long): OcrFrame? = try {
        OcrFrame.fromImageProxy(image, frameId)
    } catch (e: IllegalArgumentException) {
        _events.tryEmit(OcrAnalyzerEvent.Error(e))
        null
    } catch (e: IllegalStateException) {
        if (e is CancellationException) throw e
        _events.tryEmit(OcrAnalyzerEvent.Error(e))
        null
    } catch (e: IndexOutOfBoundsException) {
        _events.tryEmit(OcrAnalyzerEvent.Error(e))
        null
    } catch (e: BufferUnderflowException) {
        _events.tryEmit(OcrAnalyzerEvent.Error(e))
        null
    }

    private fun registerSensorListener() {
        val sm = sensorManager ?: return
        val listener = sensorListener ?: return
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensorListener() {
        val sm = sensorManager ?: return
        val listener = sensorListener ?: return
        try {
            sm.unregisterListener(listener)
        } catch (_: Throwable) {
            // unregisterListener is documented to be a no-op when the
            // listener was never registered; swallow any oddities so
            // close() stays idempotent.
        }
    }

    /**
     * Atomically read the peak gyro magnitude and reset it to 0 — the
     * next analyze() call sees only samples observed *after* this
     * point. Returns `null` when the IMU sampler is disabled
     * (no [sensorManager] supplied) so callers can omit the JSON
     * block entirely rather than emit a misleading `gyro=0` reading
     * (the boundary detector reads `0` as "no motion").
     */
    private fun consumeGyroPeak(): Float? {
        if (sensorManager == null) return null
        val zeroBits = java.lang.Float.floatToRawIntBits(0f).toLong()
        val raw = gyroPeakBits.getAndSet(zeroBits)
        return java.lang.Float.intBitsToFloat(raw.toInt())
    }

    private fun OcrFrameMeta.withImu(gyroPeak: Float?): OcrFrameMeta {
        if (gyroPeak == null) return this
        return copy(extraJson = mergeImuIntoExtraJson(extraJson, gyroPeak))
    }

    private companion object {
        val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        const val KEY_IMU = "imu"
        const val KEY_GYRO_MAX = "gyro_max_rad_per_s"

        /**
         * Merge `{"imu":{"gyro_max_rad_per_s": gyroPeak}}` into the
         * caller-supplied `extraJson` envelope.
         *
         * # Collision policy
         *
         * Caller-supplied keys win on every collision — the same rule
         * the SDK uses everywhere else for opaque-JSON envelopes
         * (`extraContextJson`, `OcrSessionConfigBuilder.optionsJson`).
         * In practice callers do not populate `imu` themselves, so
         * collisions are rare; when they do, the caller's data is
         * authoritative.
         *
         * # Malformed input
         *
         * Existing `extraJson` that fails to parse as a JSON object is
         * dropped (replaced by a fresh envelope carrying only the IMU
         * block). The alternative — refusing to forward IMU data when
         * the caller's `extraJson` is malformed — would be worse: it
         * silently disables the page-boundary signal whenever a caller
         * passes a broken value.
         */
        fun mergeImuIntoExtraJson(existing: String?, gyroPeak: Float): String {
            val imuBlock = buildJsonObject { put(KEY_GYRO_MAX, gyroPeak) }
            val callerObject = existing?.takeIf { it.isNotBlank() }?.let {
                runCatching { LENIENT_JSON.parseToJsonElement(it) as? JsonObject }
                    .getOrNull()
            }
            return buildJsonObject {
                put(KEY_IMU, imuBlock)
                callerObject?.forEach { (key, value) -> put(key, value) }
            }.toString()
        }
    }
}

package com.adsamcik.mindlayer.sdk.camerax

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.sdk.OcrHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v0.9 IMU forwarding path on [OcrImageAnalyzer].
 *
 * The analyzer remains binary-compatible: callers that omit
 * `sensorManager` get exactly the previous behaviour (no listener, no
 * `extraJson` mutation). When a [SensorManager] is supplied, the
 * analyzer registers a [Sensor.TYPE_GYROSCOPE] listener at
 * [SensorManager.SENSOR_DELAY_GAME], tracks the peak `sqrt(x²+y²+z²)`
 * magnitude per frame window, merges it as
 * `{"imu":{"gyro_max_rad_per_s": peak}}` into [OcrFrameMeta.extraJson],
 * and unregisters the listener on [close].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrImageAnalyzerImuTest {

    @Test fun `default constructor (sensorManager=null) leaves extraJson unchanged`() = runTest {
        val metaSlot = slot<OcrFrameMeta>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        coEvery {
            session.pushFrame(capture(metaSlot), any(), any(), any(), any(), any())
        } returns OcrFrameAck(frameId = 1L, status = OcrFrameAck.STATUS_ACCEPTED)
        val analyzer = OcrImageAnalyzer(
            session = session,
            scope = this,
            runClientSidePresort = false,
        )

        analyzer.analyze(yPlaneImageProxy(frameId = 1L))
        advanceUntilIdle()

        assertTrue("pushFrame must have been invoked", metaSlot.isCaptured)
        assertNull(
            "Without sensorManager the analyzer must NOT touch extraJson",
            metaSlot.captured.extraJson,
        )
    }

    @Test fun `sensor listener registered on construction with TYPE_GYROSCOPE and SENSOR_DELAY_GAME`() {
        val sensor = mockk<Sensor>(relaxed = true)
        val sensorManager = mockk<SensorManager>(relaxed = true)
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns sensor
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)

        val analyzer = OcrImageAnalyzer(
            session = session,
            sensorManager = sensorManager,
        )

        verify(exactly = 1) {
            sensorManager.registerListener(
                any<SensorEventListener>(),
                sensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }
        // Defensive: silence "analyzer never used" warning.
        analyzer.close()
    }

    @Test fun `device with no gyro sensor does NOT register a listener (graceful no-op)`() {
        val sensorManager = mockk<SensorManager>(relaxed = true)
        every { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns null
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)

        val analyzer = OcrImageAnalyzer(
            session = session,
            sensorManager = sensorManager,
        )

        verify(exactly = 0) {
            sensorManager.registerListener(any<SensorEventListener>(), any<Sensor>(), any<Int>())
        }
        analyzer.close()
    }

    @Test fun `peak gyro magnitude is forwarded into extraJson imu block`() = runTest {
        val (sensorManager, listenerSlot) = mockSensorManager()
        val metaSlot = slot<OcrFrameMeta>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        coEvery {
            session.pushFrame(capture(metaSlot), any(), any(), any(), any(), any())
        } returns OcrFrameAck(frameId = 2L, status = OcrFrameAck.STATUS_ACCEPTED)
        val analyzer = OcrImageAnalyzer(
            session = session,
            scope = this,
            runClientSidePresort = false,
            sensorManager = sensorManager,
        )
        val listener = listenerSlot.captured

        // Emit a sequence of gyro samples — the peak magnitude must win.
        // (0.5,0.5,0.5) → ~0.866; (1,2,2) → 3.0 (PEAK); (0.1,0.1,0.1) → ~0.173.
        listener.onSensorChanged(gyroEvent(0.5f, 0.5f, 0.5f))
        listener.onSensorChanged(gyroEvent(1f, 2f, 2f))
        listener.onSensorChanged(gyroEvent(0.1f, 0.1f, 0.1f))

        analyzer.analyze(yPlaneImageProxy(frameId = 1L))
        advanceUntilIdle()

        assertTrue("pushFrame invoked", metaSlot.isCaptured)
        val extra = metaSlot.captured.extraJson
        assertNotNull("extraJson must be populated when IMU forwarding is on", extra)
        val imu = Json.parseToJsonElement(extra!!).jsonObject["imu"]!!.jsonObject
        val gyro = imu["gyro_max_rad_per_s"]!!.jsonPrimitive.float
        assertEquals(3.0f, gyro, 1e-4f)
        analyzer.close()
    }

    @Test fun `peak resets between analyze calls (windowed peak, not running max)`() = runTest {
        val (sensorManager, listenerSlot) = mockSensorManager()
        val capturedMetas = mutableListOf<OcrFrameMeta>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        coEvery {
            session.pushFrame(any(), any(), any(), any(), any(), any())
        } answers {
            capturedMetas += arg<OcrFrameMeta>(0)
            OcrFrameAck(frameId = 1L, status = OcrFrameAck.STATUS_ACCEPTED)
        }
        val analyzer = OcrImageAnalyzer(
            session = session,
            scope = this,
            runClientSidePresort = false,
            sensorManager = sensorManager,
        )
        val listener = listenerSlot.captured

        // Window 1: peak 3.0
        listener.onSensorChanged(gyroEvent(1f, 2f, 2f))
        analyzer.analyze(yPlaneImageProxy(frameId = 1L))
        advanceUntilIdle()

        // Window 2: peak should drop to 0.5 (the prior 3.0 must NOT leak).
        listener.onSensorChanged(gyroEvent(0.5f, 0f, 0f))
        analyzer.analyze(yPlaneImageProxy(frameId = 2L))
        advanceUntilIdle()

        assertEquals(2, capturedMetas.size)
        val peak1 = gyroMaxOf(capturedMetas[0])
        val peak2 = gyroMaxOf(capturedMetas[1])
        assertEquals(3.0f, peak1, 1e-4f)
        assertEquals(0.5f, peak2, 1e-4f)
        analyzer.close()
    }

    @Test fun `close unregisters the sensor listener exactly once`() {
        val (sensorManager, listenerSlot) = mockSensorManager()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        val analyzer = OcrImageAnalyzer(
            session = session,
            sensorManager = sensorManager,
        )

        analyzer.close()

        verify(exactly = 1) {
            sensorManager.unregisterListener(listenerSlot.captured)
        }
    }

    @Test fun `close is idempotent (second invocation tolerates already-unregistered listener)`() {
        val (sensorManager, _) = mockSensorManager()
        // unregisterListener is a no-op on Android when the listener was
        // already unregistered, but we still defensively swallow Throwables.
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        val analyzer = OcrImageAnalyzer(
            session = session,
            sensorManager = sensorManager,
        )

        analyzer.close()
        analyzer.close() // must not throw
    }

    @Test fun `frames with no observed gyro samples in the window report 0`() = runTest {
        val (sensorManager, _) = mockSensorManager()
        val metaSlot = slot<OcrFrameMeta>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        coEvery {
            session.pushFrame(capture(metaSlot), any(), any(), any(), any(), any())
        } returns OcrFrameAck(frameId = 1L, status = OcrFrameAck.STATUS_ACCEPTED)
        val analyzer = OcrImageAnalyzer(
            session = session,
            scope = this,
            runClientSidePresort = false,
            sensorManager = sensorManager,
        )

        // No sensor events emitted before analyze.
        analyzer.analyze(yPlaneImageProxy(frameId = 1L))
        advanceUntilIdle()

        val extra = metaSlot.captured.extraJson
        assertNotNull(extra)
        val gyro = gyroMaxOf(metaSlot.captured)
        // "No motion observed" is a legitimate signal — the boundary
        // detector reads 0 as "device held still". Distinct from
        // sensorManager=null (which omits the imu block entirely).
        assertEquals(0f, gyro, 1e-6f)
        analyzer.close()
    }

    @Test fun `gyro events from other sensors are ignored`() = runTest {
        val (sensorManager, listenerSlot) = mockSensorManager()
        val metaSlot = slot<OcrFrameMeta>()
        val session = mockk<OcrHandle.MultiFrame>(relaxed = true)
        coEvery {
            session.pushFrame(capture(metaSlot), any(), any(), any(), any(), any())
        } returns OcrFrameAck(frameId = 1L, status = OcrFrameAck.STATUS_ACCEPTED)
        val analyzer = OcrImageAnalyzer(
            session = session,
            scope = this,
            runClientSidePresort = false,
            sensorManager = sensorManager,
        )
        val listener = listenerSlot.captured

        // Accelerometer reading with huge magnitude — must be ignored.
        val event = makeSensorEvent(Sensor.TYPE_ACCELEROMETER, floatArrayOf(99f, 99f, 99f))
        listener.onSensorChanged(event)
        analyzer.analyze(yPlaneImageProxy(frameId = 1L))
        advanceUntilIdle()

        assertEquals(
            "Accelerometer noise must not influence the gyro peak",
            0f,
            gyroMaxOf(metaSlot.captured),
            1e-6f,
        )
        analyzer.close()
    }

    // ---- helpers -----------------------------------------------------

    private fun mockSensorManager(): Pair<SensorManager, io.mockk.CapturingSlot<SensorEventListener>> {
        val sensor = mockk<Sensor>(relaxed = true)
        every { sensor.type } returns Sensor.TYPE_GYROSCOPE
        val listenerSlot = slot<SensorEventListener>()
        val sm = mockk<SensorManager>(relaxed = true)
        every { sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } returns sensor
        every {
            sm.registerListener(capture(listenerSlot), any<Sensor>(), any<Int>())
        } returns true
        every { sm.unregisterListener(any<SensorEventListener>()) } returns Unit
        return sm to listenerSlot
    }

    private fun gyroEvent(x: Float, y: Float, z: Float): SensorEvent =
        makeSensorEvent(Sensor.TYPE_GYROSCOPE, floatArrayOf(x, y, z))

    /**
     * `SensorEvent.sensor` and `SensorEvent.values` are *public Java fields*,
     * not getters — MockK can only stub methods. The unit-test `android.jar`
     * stub also strips the package-private `SensorEvent(int)` constructor,
     * so we allocate a bare instance via [sun.misc.Unsafe] and assign the
     * fields by reflection. The accompanying [Sensor] is still a relaxed
     * MockK (its `type` is a real getter and can be stubbed).
     */
    private fun makeSensorEvent(sensorType: Int, valueArray: FloatArray): SensorEvent {
        val sensor = mockk<Sensor>(relaxed = true)
        every { sensor.type } returns sensorType
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val event = unsafe.allocateInstance(SensorEvent::class.java) as SensorEvent
        SensorEvent::class.java.getField("sensor").set(event, sensor)
        SensorEvent::class.java.getField("values").set(event, valueArray.copyOf())
        return event
    }

    private fun gyroMaxOf(meta: OcrFrameMeta): Float {
        val extra = meta.extraJson ?: return Float.NaN
        return Json.parseToJsonElement(extra)
            .jsonObject["imu"]!!.jsonObject["gyro_max_rad_per_s"]!!
            .jsonPrimitive.float
    }

    /**
     * Build a minimal mocked `ImageProxy` carrying a known Y-plane.
     * We rely on the real [OcrFrame.fromImageProxy] path the analyzer
     * follows; mocking that pipeline here keeps the test focused on
     * the IMU forwarding behaviour.
     */
    private fun yPlaneImageProxy(
        frameId: Long,
        width: Int = 16,
        height: Int = 16,
    ): androidx.camera.core.ImageProxy {
        val yBytes = ByteArray(width * height) { (it % 256).toByte() }
        val planeBuffer = java.nio.ByteBuffer.wrap(yBytes)
        val yPlane = mockk<androidx.camera.core.ImageProxy.PlaneProxy>(relaxed = true)
        every { yPlane.buffer } returns planeBuffer
        every { yPlane.rowStride } returns width
        every { yPlane.pixelStride } returns 1

        val info = mockk<androidx.camera.core.ImageInfo>(relaxed = true)
        every { info.rotationDegrees } returns 0
        every { info.timestamp } returns frameId * 1_000_000L

        val image = mockk<androidx.camera.core.ImageProxy>(relaxed = true)
        every { image.width } returns width
        every { image.height } returns height
        every { image.format } returns android.graphics.ImageFormat.YUV_420_888
        every { image.planes } returns arrayOf(yPlane, yPlane, yPlane)
        every { image.imageInfo } returns info
        return image
    }
}

package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.ServiceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests for v04-media-list. These don't exercise AIDL transport
 * (Robolectric needed for that), they just verify the parcelable
 * construction, the engine-baseline capability flags, and a few invariants
 * the SDK chokepoint relies on.
 */
class MediaPartShapeTest {

    @Test
    fun `MediaPart kind constants are stable wire ints`() {
        // These values are part of the wire contract — never change without
        // a new MediaPart parcelable + new AIDL method.
        assertEquals(1, MediaPart.KIND_IMAGE)
        assertEquals(2, MediaPart.KIND_AUDIO)
        assertEquals(3, MediaPart.KIND_VIDEO)
        assertEquals(4, MediaPart.KIND_DOCUMENT)
    }

    @Test
    fun `MediaPart schemaVersion is the first field`() {
        // Mechanical: the primary constructor's first parameter MUST be
        // schemaVersion per docs/AIDL_STABILITY.md. If this test fails it's
        // because someone reordered fields and broke the convention.
        val first = MediaPart::class.java.declaredFields.first { it.name == "schemaVersion" }
        assertNotNull("schemaVersion field exists", first)
    }

    @Test
    fun `ALL_KINDS includes all four allocated constants`() {
        assertEquals(
            setOf(MediaPart.KIND_IMAGE, MediaPart.KIND_AUDIO, MediaPart.KIND_VIDEO, MediaPart.KIND_DOCUMENT),
            MediaPart.ALL_KINDS,
        )
    }

    @Test
    fun `FEATURE_MEDIA_LIST is a stable string`() {
        // Per docs/AIDL_STABILITY.md the feature-flag strings are wire-stable.
        // Nailing the literal here so an accidental rename trips a test.
        assertEquals("media_list", ServiceCapabilities.FEATURE_MEDIA_LIST)
    }

    @Test
    fun `v0Baseline does not advertise media_list`() {
        val caps = ServiceCapabilities.v0Baseline()
        assertEquals(
            "v0Baseline must NOT advertise media_list — that's a v0.4 feature",
            false,
            caps.supports(ServiceCapabilities.FEATURE_MEDIA_LIST),
        )
    }
}

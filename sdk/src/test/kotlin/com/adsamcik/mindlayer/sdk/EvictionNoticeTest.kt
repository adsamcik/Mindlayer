package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionNoticeTest {

    @Test
    fun `codeName resolves known reason codes`() {
        assertEquals("SESSION_EVICTED", EvictionNotice("s1", MindlayerErrorCode.SESSION_EVICTED).codeName)
        assertEquals("SESSION_EXPIRED", EvictionNotice("s1", MindlayerErrorCode.SESSION_EXPIRED).codeName)
        assertEquals("MEMORY_PRESSURE", EvictionNotice("s1", MindlayerErrorCode.MEMORY_PRESSURE).codeName)
    }

    @Test
    fun `codeName is null for unknown codes`() {
        assertNull(EvictionNotice("s1", reasonCode = 99999).codeName)
    }

    @Test
    fun `isMemoryPressure is true only for MEMORY_PRESSURE`() {
        assertTrue(EvictionNotice("s1", MindlayerErrorCode.MEMORY_PRESSURE).isMemoryPressure)
        assertFalse(EvictionNotice("s1", MindlayerErrorCode.SESSION_EVICTED).isMemoryPressure)
        assertFalse(EvictionNotice("s1", MindlayerErrorCode.SESSION_EXPIRED).isMemoryPressure)
    }
}

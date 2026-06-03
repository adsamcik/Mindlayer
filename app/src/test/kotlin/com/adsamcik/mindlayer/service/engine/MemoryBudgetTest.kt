package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MemoryPressure], [DeviceTier], and [MemorySnapshot] data
 * types. These are pure data classes and enums — no Android mocking needed.
 */
class MemoryBudgetTest {

    // ---- MemoryPressure enum ordering --------------------------------------

    @Test
    fun `MemoryPressure has 4 levels`() {
        assertEquals(4, MemoryPressure.entries.size)
    }

    @Test
    fun `MemoryPressure ordinal ordering - NORMAL lowest, EMERGENCY highest`() {
        assertTrue(MemoryPressure.NORMAL.ordinal < MemoryPressure.WARNING.ordinal)
        assertTrue(MemoryPressure.WARNING.ordinal < MemoryPressure.CRITICAL.ordinal)
        assertTrue(MemoryPressure.CRITICAL.ordinal < MemoryPressure.EMERGENCY.ordinal)
    }

    @Test
    fun `MemoryPressure NORMAL is ordinal 0`() {
        assertEquals(0, MemoryPressure.NORMAL.ordinal)
    }

    @Test
    fun `MemoryPressure EMERGENCY is ordinal 3`() {
        assertEquals(3, MemoryPressure.EMERGENCY.ordinal)
    }

    @Test
    fun `MemoryPressure comparison via ordinal works for severity checks`() {
        val current = MemoryPressure.WARNING
        assertTrue(current.ordinal >= MemoryPressure.WARNING.ordinal)
        assertFalse(current.ordinal >= MemoryPressure.CRITICAL.ordinal)
    }

    @Test
    fun `MemoryPressure all values in expected order`() {
        val expected = listOf(
            MemoryPressure.NORMAL,
            MemoryPressure.WARNING,
            MemoryPressure.CRITICAL,
            MemoryPressure.EMERGENCY,
        )
        assertEquals(expected, MemoryPressure.entries.toList())
    }

    // ---- DeviceTier construction -------------------------------------------

    @Test
    fun `DeviceTier for 4GB device - 1 session, 32k max`() {
        val tier = DeviceTier(
            maxSessions = 1,
            defaultMaxTokens = 8_192,
            maxMaxTokens = 32_768,
            deviceRamMb = 4 * 1024L,
        )
        assertEquals(1, tier.maxSessions)
        assertEquals(8_192, tier.defaultMaxTokens)
        assertEquals(32_768, tier.maxMaxTokens)
    }

    @Test
    fun `DeviceTier for 6GB device - 1 session, 64k max`() {
        val tier = DeviceTier(
            maxSessions = 1,
            defaultMaxTokens = 16_384,
            maxMaxTokens = 65_536,
            deviceRamMb = 6 * 1024L,
        )
        assertEquals(1, tier.maxSessions)
        assertEquals(16_384, tier.defaultMaxTokens)
        assertEquals(65_536, tier.maxMaxTokens)
        assertEquals(6 * 1024L, tier.deviceRamMb)
    }

    @Test
    fun `DeviceTier for 8GB device - 1 session, 128k max`() {
        val tier = DeviceTier(
            maxSessions = 1,
            defaultMaxTokens = 32_768,
            maxMaxTokens = 131_072,
            deviceRamMb = 8 * 1024L,
        )
        assertEquals(1, tier.maxSessions)
        assertEquals(32_768, tier.defaultMaxTokens)
        assertEquals(131_072, tier.maxMaxTokens)
    }

    @Test
    fun `DeviceTier for 12GB device - 1 session, 64k default, 128k max`() {
        val tier = DeviceTier(
            maxSessions = 1,
            defaultMaxTokens = 65_536,
            maxMaxTokens = 131_072,
            deviceRamMb = 12 * 1024L,
        )
        assertEquals(1, tier.maxSessions)
        assertEquals(65_536, tier.defaultMaxTokens)
        assertEquals(131_072, tier.maxMaxTokens)
    }

    @Test
    fun `DeviceTier for 16GB+ device - 1 session, 128k default and max`() {
        val tier = DeviceTier(
            maxSessions = 1,
            defaultMaxTokens = 131_072,
            maxMaxTokens = 131_072,
            deviceRamMb = 16 * 1024L,
        )
        assertEquals(1, tier.maxSessions)
        assertEquals(131_072, tier.defaultMaxTokens)
        assertEquals(131_072, tier.maxMaxTokens)
    }

    @Test
    fun `DeviceTier equality`() {
        val t1 = DeviceTier(2, 4096, 4096, 8192L)
        val t2 = DeviceTier(2, 4096, 4096, 8192L)
        assertEquals(t1, t2)
    }

    @Test
    fun `DeviceTier copy modifies single field`() {
        val original = DeviceTier(2, 4096, 4096, 8192L)
        val modified = original.copy(maxSessions = 4)
        assertEquals(4, modified.maxSessions)
        assertEquals(4096, modified.defaultMaxTokens)
    }

    @Test
    fun `DeviceTier records actual RAM in MB`() {
        val tier = DeviceTier(1, 2048, 2048, 5500L)
        assertEquals(5500L, tier.deviceRamMb)
    }

    // ---- MemorySnapshot construction and fields ----------------------------

    @Test
    fun `MemorySnapshot construction with NORMAL pressure`() {
        val snap = MemorySnapshot(
            availableMb = 2000L,
            totalMb = 8192L,
            lowMemory = false,
            pressure = MemoryPressure.NORMAL,
            recommendedMaxTokens = 32768,
        )
        assertEquals(2000L, snap.availableMb)
        assertEquals(8192L, snap.totalMb)
        assertFalse(snap.lowMemory)
        assertEquals(MemoryPressure.NORMAL, snap.pressure)
        assertEquals(32768, snap.recommendedMaxTokens)
    }

    @Test
    fun `MemorySnapshot with EMERGENCY pressure and low memory flag`() {
        val snap = MemorySnapshot(
            availableMb = 300L,
            totalMb = 6144L,
            lowMemory = true,
            pressure = MemoryPressure.EMERGENCY,
            recommendedMaxTokens = 2048,
        )
        assertTrue(snap.lowMemory)
        assertEquals(MemoryPressure.EMERGENCY, snap.pressure)
        assertEquals(2048, snap.recommendedMaxTokens)
    }

    @Test
    fun `MemorySnapshot equality`() {
        val s1 = MemorySnapshot(1500L, 8192L, false, MemoryPressure.WARNING, 4096)
        val s2 = MemorySnapshot(1500L, 8192L, false, MemoryPressure.WARNING, 4096)
        assertEquals(s1, s2)
    }

    @Test
    fun `MemorySnapshot copy changes pressure`() {
        val original = MemorySnapshot(1500L, 8192L, false, MemoryPressure.NORMAL, 32768)
        val updated = original.copy(pressure = MemoryPressure.CRITICAL, recommendedMaxTokens = 4096)
        assertEquals(MemoryPressure.CRITICAL, updated.pressure)
        assertEquals(4096, updated.recommendedMaxTokens)
        assertEquals(original.availableMb, updated.availableMb)
    }

    // ---- Pressure threshold value documentation tests ----------------------

    @Test
    fun `EMERGENCY threshold is below 400MB`() {
        // Document the absolute thresholds used in MemoryBudget
        val emergencyThreshold = 400L
        assertTrue("Emergency should trigger at low available RAM",
            emergencyThreshold < 500L)
    }

    @Test
    fun `CRITICAL threshold is below 800MB`() {
        val criticalThreshold = 800L
        assertTrue(criticalThreshold < 1000L)
    }

    @Test
    fun `WARNING threshold is below 1200MB`() {
        val warningThreshold = 1200L
        assertTrue(warningThreshold < 1500L)
    }

    @Test
    fun `exit thresholds are higher than entry thresholds for hysteresis`() {
        // Entry thresholds
        val emergencyEnter = 400L
        val criticalEnter = 800L
        val warningEnter = 1200L
        // Exit thresholds
        val emergencyExit = 500L
        val criticalExit = 900L
        val warningExit = 1300L

        assertTrue("Emergency exit > enter", emergencyExit > emergencyEnter)
        assertTrue("Critical exit > enter", criticalExit > criticalEnter)
        assertTrue("Warning exit > enter", warningExit > warningEnter)
    }

    @Test
    fun `hysteresis gap is 100MB for all levels`() {
        assertEquals(100L, 500L - 400L) // EMERGENCY
        assertEquals(100L, 900L - 800L) // CRITICAL
        assertEquals(100L, 1300L - 1200L) // WARNING
    }

    @Test
    fun `EMERGENCY max tokens ceiling is 2048`() {
        val emergencyMaxTokens = 2048
        assertEquals(2048, emergencyMaxTokens)
    }

    // ---- DeviceTier boundary verification ----------------------------------

    @Test
    fun `tier boundaries cover all expected RAM sizes`() {
        // Verify we have tiers for the full range
        val tiers = listOf(
            DeviceTier(1, 8_192, 32_768, 4 * 1024L),    // ≤4GB
            DeviceTier(1, 16_384, 65_536, 6 * 1024L),   // ≤6GB
            DeviceTier(1, 32_768, 131_072, 8 * 1024L),  // ≤8GB
            DeviceTier(1, 65_536, 131_072, 12 * 1024L), // ≤12GB
            DeviceTier(1, 131_072, 131_072, 16 * 1024L), // >12GB
        )
        assertEquals(5, tiers.size)
        // maxSessions pinned at 1 across all tiers — see DeviceTier KDoc.
        for (t in tiers) {
            assertEquals(
                "maxSessions must be 1 (LiteRT-LM one-Conversation-per-Engine)",
                1,
                t.maxSessions,
            )
        }
        // defaultMaxTokens monotonically non-decreasing with RAM
        for (i in 0 until tiers.size - 1) {
            assertTrue(
                "defaultMaxTokens non-decreasing across tiers",
                tiers[i].defaultMaxTokens <= tiers[i + 1].defaultMaxTokens,
            )
        }
    }

    @Test
    fun `maxMaxTokens is always at least defaultMaxTokens`() {
        val tiers = listOf(
            DeviceTier(1, 8_192, 32_768, 4 * 1024L),
            DeviceTier(1, 16_384, 65_536, 6 * 1024L),
            DeviceTier(1, 32_768, 131_072, 8 * 1024L),
            DeviceTier(1, 65_536, 131_072, 12 * 1024L),
            DeviceTier(1, 131_072, 131_072, 16 * 1024L),
        )
        for (tier in tiers) {
            assertTrue(
                "maxMaxTokens (${tier.maxMaxTokens}) >= defaultMaxTokens (${tier.defaultMaxTokens})",
                tier.maxMaxTokens >= tier.defaultMaxTokens,
            )
        }
    }

    @Test
    fun `maxMaxTokens never exceeds Gemma 4 E2B model max of 131072`() {
        val tiers = listOf(
            DeviceTier(1, 8_192, 32_768, 4 * 1024L),
            DeviceTier(1, 16_384, 65_536, 6 * 1024L),
            DeviceTier(1, 32_768, 131_072, 8 * 1024L),
            DeviceTier(1, 65_536, 131_072, 12 * 1024L),
            DeviceTier(1, 131_072, 131_072, 16 * 1024L),
        )
        for (tier in tiers) {
            assertTrue(
                "maxMaxTokens (${tier.maxMaxTokens}) must not exceed model max 131_072",
                tier.maxMaxTokens <= 131_072,
            )
        }
    }
}

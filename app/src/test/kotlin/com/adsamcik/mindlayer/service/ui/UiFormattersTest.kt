package com.adsamcik.mindlayer.service.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Unit tests for [formatWholeNumber].
 *
 * The dashboard renders token counts / memory figures through these helpers, so
 * they must apply locale-aware grouping. The default locale is pinned to US for
 * deterministic assertions, then restored.
 */
class UiFormattersTest {

    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `int values are grouped with thousands separators`() {
        assertEquals("0", formatWholeNumber(0))
        assertEquals("999", formatWholeNumber(999))
        assertEquals("1,000", formatWholeNumber(1_000))
        assertEquals("1,234,567", formatWholeNumber(1_234_567))
    }

    @Test
    fun `negative int values keep the sign`() {
        assertEquals("-2,048", formatWholeNumber(-2_048))
    }

    @Test
    fun `long values are grouped with thousands separators`() {
        assertEquals("0", formatWholeNumber(0L))
        assertEquals("1,000", formatWholeNumber(1_000L))
        assertEquals("9,876,543,210", formatWholeNumber(9_876_543_210L))
    }
}

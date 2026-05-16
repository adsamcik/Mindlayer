package com.adsamcik.mindlayer.service

import android.os.Parcel
import android.os.Parcelable
import com.adsamcik.mindlayer.ServiceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceCapabilitiesEmbeddingTest {
    @Test fun `v0 baseline does not include embeddings and zeros limits`() {
        val c = ServiceCapabilities.v0Baseline()
        assertFalse(c.supports(ServiceCapabilities.FEATURE_EMBEDDINGS))
        assertEquals(0, c.maxEmbeddingBatchInline)
        assertEquals(0, c.maxEmbeddingBatchShm)
        assertEquals(0, c.maxEmbeddingBatchTotal)
        assertEquals(0L, c.maxEmbeddingInputBytes)
        assertEquals(emptyList<String>(), c.embeddingModelIds)
        assertEquals(emptyList<Int>(), c.embeddingDims)
    }

    @Test fun `v1 baseline keeps schema one with embedding defaults`() {
        val c = ServiceCapabilities.v1Baseline()
        assertEquals(1, c.schemaVersion)
        assertEquals(0, c.maxEmbeddingBatchInline)
        assertEquals(0, c.maxEmbeddingBatchShm)
        assertEquals(0, c.maxEmbeddingBatchTotal)
        assertEquals(0L, c.maxEmbeddingInputBytes)
    }

    @Test fun `v1 shaped parcel rehydrates defaults under v2 code`() {
        val parcel = Parcel.obtain()
        try {
            ServiceCapabilities.v1Baseline().writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            val creator = ServiceCapabilities::class.java.getField("CREATOR").get(null) as Parcelable.Creator<ServiceCapabilities>
            val c = creator.createFromParcel(parcel)
            assertEquals(1, c.schemaVersion)
            assertEquals(0, c.maxEmbeddingBatchInline)
            assertEquals(0L, c.maxEmbeddingInputBytes)
        } finally {
            parcel.recycle()
        }
    }
}
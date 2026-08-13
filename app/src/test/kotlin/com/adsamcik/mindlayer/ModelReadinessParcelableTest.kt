package com.adsamcik.mindlayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelReadinessParcelableTest {
    private inline fun <reified T : Parcelable> roundtrip(value: T): T {
        val parcel = Parcel.obtain()
        return try {
            value.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            val restored = Parcel.obtain()
            try {
                restored.unmarshall(bytes, 0, bytes.size)
                restored.setDataPosition(0)
                @Suppress("UNCHECKED_CAST")
                val creator = T::class.java.getField("CREATOR").get(null) as Parcelable.Creator<T>
                creator.createFromParcel(restored)
            } finally {
                restored.recycle()
            }
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `readiness item and snapshot roundtrip`() {
        val item = ModelReadinessItem(
            family = ModelReadinessItem.FAMILY_OCR,
            state = ModelReadinessItem.STATE_SETUP_REQUIRED,
            reasonCode = ModelReadinessItem.REASON_MODEL_MISSING,
            extensionsJson = """{"source":"test"}""",
        )
        val snapshot = ModelReadinessSnapshot(
            capturedAtEpochMs = 42L,
            items = listOf(item),
            extensionsJson = """{"future":true}""",
        )

        assertEquals(item, roundtrip(item))
        assertEquals(snapshot, roundtrip(snapshot))
    }

    @Test
    fun `setup action roundtrips`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pendingIntent = PendingIntent.getActivity(
            context,
            7,
            Intent(context, javaClass),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = ModelSetupAction(
            family = ModelReadinessItem.FAMILY_CHAT,
            setupIntent = pendingIntent,
        )

        val restored = roundtrip(action)
        assertEquals(action.family, restored.family)
        assertEquals(action.schemaVersion, restored.schemaVersion)
        assertEquals(action.setupIntent.creatorPackage, restored.setupIntent.creatorPackage)
    }
}

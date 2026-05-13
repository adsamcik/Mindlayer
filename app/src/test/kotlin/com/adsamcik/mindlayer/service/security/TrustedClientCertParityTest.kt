package com.adsamcik.mindlayer.service.security

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrustedClientCertParityTest {
    @Test
    fun `manifest known certs and first-party seed hashes stay in sync`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val certArrayId = context.resources.getIdentifier(
            "mindlayer_trusted_client_certs",
            "array",
            context.packageName,
        ).takeIf { it != 0 } ?: R.array.mindlayer_trusted_client_certs
        val arrayItems = context.resources.getTextArray(certArrayId).map { it.toString() }
        val seedHashes = MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS
            .map { it.signingCertSha256 }

        assertEquals(seedHashes.size, arrayItems.size)
        assertEquals(seedHashes.toSet(), arrayItems.toSet())
        (seedHashes + arrayItems).forEach { value ->
            assertTrue("Invalid SHA-256 cert hash: $value", SHA256_HEX.matches(value))
        }
    }

    private companion object {
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
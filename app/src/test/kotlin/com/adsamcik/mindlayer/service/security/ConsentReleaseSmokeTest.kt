package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Release-variant smoke test.
 *
 * The `:app:testReleaseUnitTest` CI step (see `.github/workflows/ci.yml`) is
 * scoped in `app/build.gradle.kts` to JUST this class. Its real job is to force
 * `:app:compileReleaseKotlin` + `:app:compileReleaseUnitTestKotlin` so any
 * release-only source-set compile error fails the PR, while staying fast — the
 * rest of the `:app` unit suite is Robolectric-heavy and not release-clean, so
 * the release task is intentionally NOT run against the whole suite.
 *
 * This replaces `DebugAllowlistSeederReleaseAbsenceTest`, whose subject
 * (`DebugAllowlistSeeder`) was deleted with the v0.10 consent migration. Pure
 * JVM logic only — no Robolectric / Android framework — so it is release-clean
 * and sub-second.
 */
class ConsentReleaseSmokeTest {

    @Test
    fun `consent error codes are wire-stable`() {
        // These wire codes are part of the SDK contract; a release build must
        // expose exactly these values for the consent-Intent flow.
        assertEquals(6005, MindlayerErrorCode.CONSENT_REQUIRED)
        assertEquals(6006, MindlayerErrorCode.CONSENT_DENIED)
        assertEquals(3009, MindlayerErrorCode.INPUT_REJECTED)
    }
}

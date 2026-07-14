package com.adsamcik.mindlayer

import com.adsamcik.mindlayer.shared.ContractVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tripwire test for [ContractVersion].
 *
 * `ContractVersion.MAJOR` is hand-synchronized with `contractMajorVersion`
 * in the root `build.gradle.kts` (which itself is `require()`-checked
 * against `publishVersion`'s major at configuration time — see
 * "Product/contract version synchronization" there). This test is a
 * second, source-level tripwire: if you bump [ContractVersion.MAJOR]
 * without also bumping `contractMajorVersion` and `publishVersion`'s major
 * in lockstep, this assertion goes stale and forces you to update it
 * deliberately, which is the point — see
 * `docs/architecture/AIDL_STABILITY.md` § "Contract version and
 * compatibility policy".
 */
class ContractVersionTest {

    @Test
    fun `major matches the currently-synchronized product major`() {
        // Update this alongside contractMajorVersion in the root
        // build.gradle.kts and publishVersion's major, in the same PR.
        assertEquals(1, ContractVersion.MAJOR)
    }

    @Test
    fun `version string matches its components`() {
        assertEquals(
            "${ContractVersion.MAJOR}.${ContractVersion.MINOR}.${ContractVersion.PATCH}",
            ContractVersion.VERSION,
        )
    }

    @Test
    fun `components are non-negative`() {
        assertEquals(true, ContractVersion.MAJOR >= 0)
        assertEquals(true, ContractVersion.MINOR >= 0)
        assertEquals(true, ContractVersion.PATCH >= 0)
    }
}

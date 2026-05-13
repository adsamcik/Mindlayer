package com.adsamcik.mindlayer.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MindlayerMlServiceFirstPartySeedTest {

    private val hexRegex = Regex("^[0-9a-f]{64}$")

    @Test
    fun seeds_contain_exactly_two_entries() {
        assertEquals(2, MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS.size)
    }

    @Test
    fun every_seed_has_lowercase_hex_sha256_of_64_chars() {
        for (entry in MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS) {
            assertTrue(
                "signingCertSha256 for ${entry.packageName} must be 64 lowercase hex chars, got '${entry.signingCertSha256}'",
                hexRegex.matches(entry.signingCertSha256),
            )
        }
    }

    @Test
    fun every_seed_has_non_blank_package_name() {
        for (entry in MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS) {
            assertTrue(
                "packageName must be non-blank",
                entry.packageName.isNotBlank(),
            )
        }
    }

    @Test
    fun seeds_pin_expected_first_party_packages() {
        val pkgs = MindlayerMlService.FIRST_PARTY_ALLOWLIST_SEEDS.map { it.packageName }.toSet()
        assertEquals(setOf("com.adsamcik.starlitcoffee", "com.adsamcik.expenses"), pkgs)
    }
}

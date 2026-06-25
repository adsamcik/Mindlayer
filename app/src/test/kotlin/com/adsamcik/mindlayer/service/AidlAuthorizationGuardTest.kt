package com.adsamcik.mindlayer.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Security fitness guard: EVERY external AIDL entry point must run the
 * default-deny authorization gate (`authorizeCall()`) in its [ServiceBinder]
 * override. A new AIDL method that forgets the gate would compile, pass every
 * existing behavioural test, and silently expose an un-authorized surface — this
 * static guard makes that omission fail the build instead.
 *
 * It parses the method names from `IMindlayerService.aidl` and asserts each has
 * an `override fun <name>(` in `ServiceBinder.kt` whose body calls
 * `authorizeCall(`. Methods that intentionally use a different gate are listed
 * in [EXEMPT] with a justification, so adding a new exemption is a deliberate,
 * reviewable edit.
 *
 * Complements [ServiceBinderAuthorizationTest] (which behaviourally proves the
 * gate fires for a first-time UID and that `ping` uses a ping-specific throttle).
 */
class AidlAuthorizationGuardTest {

    @Test
    fun `every external AIDL method authorizes the caller in ServiceBinder`() {
        val root = repoRoot()
        val aidl = readText(root.resolve("sdk/src/main/aidl/com/adsamcik/mindlayer/IMindlayerService.aidl"))
        val binder = readText(root.resolve("app/src/main/kotlin/com/adsamcik/mindlayer/service/ServiceBinder.kt"))

        val methods = aidlMethodNames(aidl)
        assertTrue(
            "AIDL parse found too few methods (${methods.size}) — the parser likely broke; methods=$methods",
            methods.size >= 30,
        )

        val violations = mutableListOf<String>()
        for (name in methods) {
            if (name in EXEMPT) continue
            val body = overrideBody(binder, name)
            if (body == null) {
                violations += "$name: no `override fun $name(` found in ServiceBinder"
                continue
            }
            if (!body.contains("authorizeCall(")) {
                violations += "$name: ServiceBinder override does not call authorizeCall()"
            }
        }
        assertTrue(
            "Every external AIDL method must call authorizeCall() (default-deny gate) or be an " +
                "explicitly-justified exemption. Violations:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** Method names declared in the interface body, with comments stripped. */
    private fun aidlMethodNames(aidl: String): List<String> {
        val body = aidl
            .substringAfter("interface IMindlayerService")
            .substringAfter('{')
            .substringBeforeLast('}')
        val noComments = body
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
        return Regex("(?:^|\\n)\\s*(?:@\\w+\\s+)?(?:oneway\\s+)?[\\w<>,\\[\\] ]+?\\s+(\\w+)\\s*\\(")
            .findAll(noComments)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    /** Substring of [binder] from `override fun <name>(` to the next override (or EOF). */
    private fun overrideBody(binder: String, name: String): String? {
        val marker = "override fun $name("
        val start = binder.indexOf(marker)
        if (start < 0) return null
        val next = binder.indexOf("override fun ", start + marker.length)
        return if (next < 0) binder.substring(start) else binder.substring(start, next)
    }

    private fun readText(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

    private fun repoRoot(): Path {
        var current = Paths.get("").toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Could not find repository root")
        }
        return current
    }

    private companion object {
        // Methods that deliberately bypass the standard authorizeCall() gate and
        // enforce their own gating instead. Adding a new exemption here MUST be a
        // deliberate, reviewed decision — each entry below is justified against
        // its ServiceBinder override + IMindlayerService.aidl docs.
        //
        //  - ping(): a co-signed peer in pending-approval may still confirm
        //    liveness; uses a ping-specific pre-consent throttle.
        //  - requestConsentChallenge(): the entry point for UN-approved callers,
        //    so by design it cannot run the default-deny allowlist gate. It is
        //    instead rate-limited per-UID (rateLimiter.tryAcquireConsentChallenge)
        //    with identity-verification + denial + cooldown checks.
        //  - lookupChallenge() / completeConsent(): self-UID only
        //    (Binder.getCallingUid() == Process.myUid()); ConsentActivity, which
        //    runs in Mindlayer's own process, uses them to render the prompt and
        //    submit the user's decision. See docs/architecture/CONSENT_ARCHITECTURE.md.
        private val EXEMPT = setOf(
            "ping",
            "requestConsentChallenge",
            "lookupChallenge",
            "completeConsent",
        )
    }
}

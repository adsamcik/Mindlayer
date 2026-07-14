package com.adsamcik.mindlayer.shared

/**
 * Semantic version of the Mindlayer AIDL/wire contract between the service
 * (`:app`, package `com.adsamcik.mindlayer`) and any SDK client (`:sdk`).
 *
 * This is deliberately a **separate number** from the product/SDK version
 * (`publishVersion` in the repo root `build.gradle.kts`, e.g.
 * `"1.0.0-alpha.5"`). The product version tracks releases; this version
 * tracks the shape of the wire itself. They evolve at different rates —
 * most product releases ship zero AIDL changes — but are kept linked at
 * the MAJOR level:
 *
 * - **[MAJOR] always equals the product version's major component.** The
 *   root build script (`contractMajorVersion` in `build.gradle.kts`)
 *   enforces this with a `require()` at configuration time — bumping
 *   either number without the other fails every Gradle invocation. A
 *   shared major is where we reserve the right to break wire
 *   compatibility outright, in lockstep with a product major bump.
 * - **[MINOR] and [PATCH] evolve independently** of the product version's
 *   own minor/patch, on their own cadence tied to real wire changes:
 *   MINOR bumps on any additive AIDL change (new method, new parcelable,
 *   new capability flag — see `docs/architecture/AIDL_STABILITY.md`
 *   § Process); PATCH bumps for wire-invisible fixes that don't change
 *   the contract shape at all.
 *
 * # Compatibility guarantee
 *
 * **From product `1.0.0` stable onward**: any two builds whose contract
 * versions share the same MAJOR.MINOR are guaranteed wire-compatible —
 * PATCH differences never break compatibility. A MAJOR bump is the only
 * change permitted to break wire compatibility outright, and it always
 * accompanies a product major bump (see above).
 *
 * **Before product `1.0.0`** (current status — the product is still on
 * `1.0.0-alpha.x`): no compatibility guarantee is made between any two
 * contract versions, including within the same MINOR. Pre-1.0 wire
 * changes may break compatibility at any granularity, mirroring standard
 * semver's own pre-1.0 rules. The only real compatibility signal during
 * this period is runtime capability negotiation —
 * [com.adsamcik.mindlayer.ServiceCapabilities.supportedFeatures],
 * `apiVersion`, and `schemaVersion` — which callers must keep consulting
 * regardless of what this object reports.
 *
 * This object is a human/tooling-facing summary, not itself part of the
 * wire — it is never sent over AIDL or the pipe protocol. There is no
 * cross-version negotiation need for it: both sides of any single build
 * always agree on this number by construction, since `:app` and `:sdk`
 * both compile against the same `:shared` source. Actual cross-version
 * runtime compatibility between an old SDK and a new service (or vice
 * versa) is negotiated exclusively through
 * [com.adsamcik.mindlayer.ServiceCapabilities].
 *
 * See `docs/architecture/AIDL_STABILITY.md` § "Contract version and
 * compatibility policy" for the full policy and the process for bumping
 * this version.
 */
object ContractVersion {
    /** Shared with the product version's major — see class KDoc. */
    const val MAJOR: Int = 1

    /**
     * Bumped on additive AIDL changes (new method, parcelable, or
     * capability flag). Independent of the product version's minor.
     * `1` reflects the Gemma 4 thinking-mode surface (informally "v1.1"
     * in code comments/docs) shipped after the "v1.0" audio-input
     * baseline this MAJOR was cut at.
     */
    const val MINOR: Int = 1

    /** Bumped for wire-invisible fixes. Independent of the product's patch. */
    const val PATCH: Int = 1

    /** `"$MAJOR.$MINOR.$PATCH"`, e.g. `"1.1.1"`. */
    const val VERSION: String = "$MAJOR.$MINOR.$PATCH"
}

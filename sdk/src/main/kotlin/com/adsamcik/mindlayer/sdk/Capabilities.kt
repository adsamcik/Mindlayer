package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.ServiceCapabilities

/**
 * Snapshot of what the connected Mindlayer service can do, returned by
 * [Mindlayer.awaitConnected].
 *
 * In the new (Spike E) surface, [awaitConnected][Mindlayer.awaitConnected]
 * resolves to a `Capabilities` so callers receive the negotiated feature set
 * in the same call that waits for the binder, rather than making a second
 * round-trip. C1 lands the type; the value is populated when the canonical
 * lifecycle path is wired in C2.
 *
 * @property supportedFeatures the raw `FEATURE_*` flags advertised by the
 *   service (see [ServiceCapabilities]).
 */
data class Capabilities(
    val supportedFeatures: Set<String> = emptySet(),
) {
    /** True when the service advertises [feature]. */
    fun supports(feature: String): Boolean = feature in supportedFeatures

    companion object {
        /** Capabilities of a service that advertises nothing (never-connected baseline). */
        fun none(): Capabilities = Capabilities(emptySet())

        /** Adapt a raw [ServiceCapabilities] parcel into the SDK-facing [Capabilities]. */
        fun from(caps: ServiceCapabilities): Capabilities =
            Capabilities(caps.supportedFeatures.toSet())
    }
}

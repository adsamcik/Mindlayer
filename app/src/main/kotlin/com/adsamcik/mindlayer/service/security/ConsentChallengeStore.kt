package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of in-flight consent challenges, owned by the `:ml`
 * process. See `docs/CONSENT_ARCHITECTURE.md § ConsentChallengeStore`.
 *
 * A challenge is the server-side anchor that binds a caller's verified
 * identity (UID + package + signing cert, captured via real
 * `Binder.getCallingUid()` at `requestConsentChallenge` time) to an opaque
 * single-use nonce. The nonce is embedded in a `PendingIntent` that the
 * client fires to launch `ConsentActivity`; the activity calls
 * [lookup]/[consume] to retrieve the pinned identity and record the user's
 * decision. This is the only mechanism by which an external caller's
 * identity reaches the consent UI — `ConsentActivity` never trusts
 * Activity-layer caller APIs, which do not carry verifiable identity.
 *
 * ### Why in-memory only
 *
 * Challenges are short-lived (5 min TTL) and fail-closed: if `:ml` is
 * killed between issuance and the user's decision, the nonce is lost and
 * the client must call `requestConsentChallenge` again. That retry is
 * harmless. Disk persistence would only save the occasional soft-restart
 * retry at the cost of a second HMAC file + cross-file lock coordination,
 * so it was deliberately dropped. The TTL — not disk durability — is the
 * security contract.
 *
 * ### Concurrency
 *
 * The backing map is a [ConcurrentHashMap]. [consume] uses
 * [ConcurrentHashMap.compute] so that exactly one caller wins the
 * single-use race even if two threads (or two `ConsentActivity` instances)
 * call `completeConsent` with the same nonce simultaneously. [lookup] is a
 * non-mutating read and may be called any number of times before consume.
 */
class ConsentChallengeStore(
    private val maxOutstanding: Int = DEFAULT_MAX_OUTSTANDING,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    /**
     * Server-side challenge record. Only [nonce], [expiresAtMs] and a
     * minted `PendingIntent` cross the wire (via the `ConsentChallenge`
     * parcelable); the rest of this record never leaves `:ml`.
     */
    data class ChallengeRecord(
        val nonce: String,
        val callerUid: Int,
        val packageName: String,
        val signingCertSha256: String,
        val displayName: String?,
        val installSource: String?,
        val previousSigSha256: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    private val challenges = ConcurrentHashMap<String, ChallengeRecord>()

    /**
     * Mint a fresh challenge for [callerUid]/[packageName]/[signingCertSha256]
     * and return its record (the caller — `ServiceBinder` — turns this into
     * a `ConsentChallenge` parcelable with a minted `PendingIntent`).
     *
     * Prunes expired entries on every call. If the live count would exceed
     * [maxOutstanding] after pruning, the oldest outstanding challenge is
     * evicted (FIFO) so a flood of unfinished consent flows cannot grow the
     * map without bound — the per-UID rate limit on `requestConsentChallenge`
     * is the primary defence; this is belt-and-braces.
     */
    fun issue(
        callerUid: Int,
        packageName: String,
        signingCertSha256: String,
        displayName: String?,
        installSource: String?,
        previousSigSha256: String?,
    ): ChallengeRecord {
        val now = timeSource()
        pruneExpired(now)
        if (challenges.size >= maxOutstanding) {
            evictOldest()
        }
        val nonce = generateNonce()
        val record = ChallengeRecord(
            nonce = nonce,
            callerUid = callerUid,
            packageName = packageName,
            signingCertSha256 = signingCertSha256,
            displayName = displayName,
            installSource = installSource,
            previousSigSha256 = previousSigSha256,
            createdAtMs = now,
            expiresAtMs = now + ttlMs,
        )
        challenges[nonce] = record
        return record
    }

    /**
     * Non-mutating read of the challenge bound to [nonce]. Returns `null`
     * if the nonce is unknown or has expired (an expired entry is pruned
     * as a side effect). Safe to call multiple times — the nonce is NOT
     * consumed by a lookup. `ConsentActivity` uses this to populate its UI
     * before the user has made a decision.
     */
    fun lookup(nonce: String?): ChallengeRecord? {
        if (nonce.isNullOrEmpty()) return null
        val record = challenges[nonce] ?: return null
        if (timeSource() >= record.expiresAtMs) {
            challenges.remove(nonce, record)
            return null
        }
        return record
    }

    /**
     * Atomically consume the challenge bound to [nonce]. Returns the record
     * to the single winning caller and `null` to everyone else (unknown
     * nonce, already-consumed nonce, expired nonce). After a successful
     * consume the nonce can never be used again.
     *
     * `ConsentActivity` calls this exactly once, when the user commits a
     * decision (`Approve` / any `Deny` variant). The atomic single-winner
     * guarantee means a double-fired consent or a racing second
     * `ConsentActivity` instance cannot apply two different decisions for
     * the same nonce.
     */
    fun consume(nonce: String?): ChallengeRecord? {
        if (nonce.isNullOrEmpty()) return null
        val now = timeSource()
        // compute() runs atomically per key on ConcurrentHashMap. The
        // winner reads + removes the record in one critical section;
        // any concurrent consume sees the key already gone and gets null.
        val taken = arrayOfNulls<ChallengeRecord>(1)
        challenges.compute(nonce) { _, existing ->
            if (existing != null && now < existing.expiresAtMs) {
                taken[0] = existing
            }
            // Always remove (consumed or expired) — null return deletes the key.
            null
        }
        return taken[0]
    }

    /** Current count of outstanding (issued, not yet consumed/expired) challenges. */
    fun outstandingCount(): Int {
        pruneExpired(timeSource())
        return challenges.size
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        // URL-safe Base64 without padding — opaque to the client, embeds
        // cleanly in a content URI for the per-nonce PendingIntent.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun pruneExpired(now: Long) {
        val it = challenges.entries.iterator()
        while (it.hasNext()) {
            if (now >= it.next().value.expiresAtMs) it.remove()
        }
    }

    private fun evictOldest() {
        val oldest = challenges.values.minByOrNull { it.createdAtMs } ?: return
        challenges.remove(oldest.nonce, oldest)
        MindlayerLog.w(
            TAG,
            "Evicted oldest outstanding consent challenge (cap=$maxOutstanding reached) " +
                "for pkg=${oldest.packageName}",
        )
    }

    companion object {
        private const val TAG = "ConsentChallengeStore"

        /** 256 bits of randomness — see crypto review (q/2^256 guess bound). */
        const val NONCE_BYTES = 32

        /** Default challenge TTL (5 minutes). Mirrors `ConsentChallenge.TTL_MS_DEFAULT`. */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /**
         * Belt-and-braces cap on outstanding challenges. The per-UID
         * 10/hour rate limit on `requestConsentChallenge` is the primary
         * spam defence; this bounds the map even across many UIDs.
         */
        const val DEFAULT_MAX_OUTSTANDING = 256
    }
}

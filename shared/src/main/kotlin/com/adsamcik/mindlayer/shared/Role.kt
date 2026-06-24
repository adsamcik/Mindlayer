package com.adsamcik.mindlayer.shared

/**
 * Canonical wire-vocabulary for conversation turn roles.
 *
 * The Mindlayer service treats roles as plain strings on the AIDL boundary
 * and on the pipe — keeping them as strings avoids the wire-break that
 * promoting to an enum would cause on existing Parcelables ([HistoryTurn],
 * [RequestMeta]). This object is the **single source of truth** for the
 * accepted set: the SDK uses it for client-side validation, the service
 * uses it in `IpcInputValidator.ALLOWED_ROLES`, and recovery / replay code
 * paths consume it when mapping local DB enums to wire form.
 *
 * # The four roles
 *
 * - [USER] — text or media supplied by the human caller.
 * - [MODEL] — text emitted by the LLM (LiteRT-LM canonicalises this as
 *   `Message.model(...)`). Some legacy SDK call sites and KDoc comments
 *   refer to this as "assistant" — that string is **not** valid on the
 *   wire and is rejected by the validator. Convert at the boundary.
 * - [TOOL] — output produced by an SDK-supplied tool, fed back into the
 *   model via `submitToolResult`.
 * - [SYSTEM] — system / instructional context, typically only used in
 *   [com.adsamcik.mindlayer.SessionConfig.systemPrompt] and not as a turn
 *   role. Accepted on the wire for future use.
 *
 * # Adding a new role
 *
 * Adding a new role is a wire-vocabulary change. Update [ALL] here, the
 * service-side `IpcInputValidator.ALLOWED_ROLES`, every `when` over roles
 * in `SessionRecovery`, `SessionManager`, and the SDK history layer.
 * Document the new role in `docs/architecture/AIDL_STABILITY.md` § "Error code
 * allocation" (we don't version roles separately — they piggy-back on
 * the AIDL surface).
 */
object Role {
    const val USER = "user"
    const val MODEL = "model"
    const val TOOL = "tool"
    const val SYSTEM = "system"

    /** All accepted wire role strings. Authoritative; consumed by validators. */
    val ALL: Set<String> = setOf(USER, MODEL, TOOL, SYSTEM)

    /** True when [value] is in [ALL]. Use for validation; null is rejected. */
    fun isValid(value: String?): Boolean = value != null && value in ALL
}

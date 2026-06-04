package com.adsamcik.mindlayer.service.ui.consent

/**
 * Detects display labels that mix characters from multiple writing systems
 * (e.g. Latin "Bank" with a Cyrillic "а"), a classic homoglyph technique for
 * making a malicious app's name imitate a trusted one.
 *
 * F-030's sanitisation strips control/format/private-use codepoints but
 * cannot catch script-mixing, so the consent UI surfaces a warning instead
 * of trying to "fix" the label. This is a user signal, not a hard block —
 * legitimate multi-script names exist, but they are rare enough that a
 * caution banner is warranted.
 */
object MixedScriptDetector {

    /**
     * Returns `true` if [label] contains letters from more than one Unicode
     * script. `COMMON` and `INHERITED` (digits, punctuation, spaces, combining
     * marks) are ignored, so "App 2.0!" is single-script. Null / blank labels
     * are not flagged.
     */
    fun isMixedScript(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val scripts = HashSet<Character.UnicodeScript>()
        var i = 0
        while (i < label.length) {
            val cp = label.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isLetter(cp)) continue
            val script = try {
                Character.UnicodeScript.of(cp)
            } catch (_: IllegalArgumentException) {
                continue
            }
            when (script) {
                Character.UnicodeScript.COMMON,
                Character.UnicodeScript.INHERITED,
                Character.UnicodeScript.UNKNOWN -> Unit
                else -> scripts.add(script)
            }
            if (scripts.size > 1) return true
        }
        return false
    }
}

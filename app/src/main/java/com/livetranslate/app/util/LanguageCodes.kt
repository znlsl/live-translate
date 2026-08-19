package com.livetranslate.app.util

/**
 * BCP-47 helpers for Live Translate.
 *
 * The API does not accept a source language: it auto-detects input and reports
 * `languageCode` on transcriptions. These helpers decide when the detected
 * input language is already the configured target (echo / passthrough).
 */
object LanguageCodes {
    data class Parsed(
        val language: String,
        val script: String?,
        val region: String?,
    )

    /**
     * True when [detected] is the same language as [target], including aliases
     * (`nb`/`no`, `cmn`/`zh`) and Chinese script/region (`zh-CN` ≈ `zh-Hans`).
     *
     * `zh` with no script matches both Simplified and Traditional. `zh-Hans`
     * does not match `zh-Hant`.
     */
    fun matchesTarget(detected: String?, target: String): Boolean {
        val d = parse(detected) ?: return false
        val t = parse(target) ?: return false
        if (d.language != t.language) return false
        val ds = effectiveScript(d)
        val ts = effectiveScript(t)
        if (ds != null && ts != null && ds != ts) return false
        return true
    }

    /**
     * Fallback when the API omits `languageCode`. Only high-confidence scripts
     * are used (Han / kana / Hangul / Latin-English) so similar Latin languages
     * are not guessed.
     */
    fun textLooksLikeTarget(text: String, target: String): Boolean {
        val t = parse(target) ?: return false
        val sample = text.trim()
        if (sample.length < 8) return false
        var han = 0
        var hangul = 0
        var kana = 0
        var latin = 0
        var letters = 0
        for (ch in sample) {
            if (ch.isLetter()) letters++
            when {
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> han++
                ch in '\u3040'..'\u30FF' || ch in '\u31F0'..'\u31FF' -> kana++
                ch in '\uAC00'..'\uD7AF' -> hangul++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        if (letters == 0) return false
        return when (t.language) {
            "zh" -> han >= 6 && han * 2 >= letters && kana == 0 && hangul == 0
            "ja" -> kana >= 2 && hangul == 0
            "ko" -> hangul >= 6
            "en" -> latin >= 8 && han == 0 && hangul == 0 && kana == 0
            else -> false
        }
    }

    fun parse(code: String?): Parsed? {
        if (code.isNullOrBlank()) return null
        val parts = code.trim().replace('_', '-').split('-').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val lang = LANG_ALIASES[parts[0].lowercase()] ?: parts[0].lowercase()
        var script: String? = null
        var region: String? = null
        for (i in 1 until parts.size) {
            val p = parts[i]
            when {
                p.length == 4 && p.all { it.isLetter() } ->
                    script = p.lowercase().replaceFirstChar { it.uppercase() }
                p.length == 2 && p.all { it.isLetter() } -> region = p.uppercase()
                p.length == 3 && p.all { it.isDigit() } -> region = p
            }
        }
        return Parsed(language = lang, script = script, region = region)
    }

    private val LANG_ALIASES = mapOf(
        "nb" to "no",
        "nn" to "no",
        "iw" to "he",
        "in" to "id",
        "jw" to "jv",
        "tl" to "fil",
        "cmn" to "zh",
        "yue" to "zh",
        "wuu" to "zh",
    )

    private fun effectiveScript(p: Parsed): String? {
        p.script?.let { return it.lowercase() }
        if (p.language == "zh") {
            return when (p.region) {
                "TW", "HK", "MO" -> "hant"
                "CN", "SG" -> "hans"
                else -> null
            }
        }
        return null
    }
}

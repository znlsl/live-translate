package com.livetranslate.app.util

/**
 * Display/export post-processor for Live Translate transcripts.
 *
 * The model streams a wall of text (same as AI Studio). This inserts `\n` after
 * sentence-ending punctuation once the current line is long enough, so the overlay
 * stays readable without fragmenting short replies like "好的。" into their own lines.
 *
 * Must not be written back into the raw accumulation buffer — the server often
 * rewrites the whole transcript, and extra newlines would break prefix matching.
 */
object TranscriptLineBreaker {
    const val DEFAULT_MIN_LINE_CHARS = 20

    private val closers = charArrayOf(
        '”', '’', '"', '\'',
        '」', '』', '）', ')', '】', '》', '>',
    )

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "jr", "sr", "vs", "etc", "inc", "ltd",
        "prof", "st", "approx", "est", "dept", "fig", "vol", "pp", "ch",
        "no", "nos", "al", "eg", "ie", "ave", "blvd", "rd",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec",
    )

    fun format(text: String, minLineChars: Int = DEFAULT_MIN_LINE_CHARS): String {
        if (text.isEmpty() || minLineChars <= 0) return text
        val cleaned = TranscriptBuffer.collapseCjkInteriorSpaces(text)
        val sb = StringBuilder(cleaned.length + 8)
        var i = 0
        var lineLen = 0
        while (i < cleaned.length) {
            val c = cleaned[i]
            if (c == '\n') {
                sb.append('\n')
                lineLen = 0
                i++
                continue
            }

            val end = sentenceEndExtent(cleaned, i)
            if (end != null) {
                sb.append(cleaned, i, end + 1)
                lineLen += end - i + 1
                i = end + 1
                val hasSpace = i < cleaned.length && cleaned[i] == ' '
                val afterSpace = if (hasSpace) i + 1 else i
                val canBreak = lineLen >= minLineChars &&
                    afterSpace < cleaned.length &&
                    cleaned[afterSpace] != '\n' &&
                    sb.lastOrNull() != '\n'
                if (canBreak) {
                    if (hasSpace) i++
                    sb.append('\n')
                    lineLen = 0
                } else if (hasSpace) {
                    sb.append(' ')
                    lineLen++
                    i++
                }
                continue
            }

            sb.append(c)
            lineLen++
            i++
        }
        return sb.toString()
    }

    /**
     * Same breaks as [format], but blank-line paragraphs so Markdown renderers
     * actually show them (a lone `\n` is treated as a space).
     */
    fun formatForMarkdown(text: String, minLineChars: Int = DEFAULT_MIN_LINE_CHARS): String {
        val lined = format(text, minLineChars)
        if (lined.isEmpty()) return lined
        return lined.replace(NEWLINE_RUN, "\n\n").trim()
    }

    /**
     * If [text] `[start]` begins a sentence terminator, return the last index of
     * that terminator plus any trailing closing quotes/brackets; otherwise null.
     */
    private fun sentenceEndExtent(text: String, start: Int): Int? {
        val c = text[start]
        var end = when {
            c == '。' || c == '．' || c == '！' || c == '？' || c == '!' || c == '?' -> start
            c == '…' -> {
                var j = start
                while (j + 1 < text.length && text[j + 1] == '…') j++
                j
            }
            c == '.' -> {
                if (start + 1 < text.length && text[start + 1] == '.') {
                    var j = start
                    while (j + 1 < text.length && text[j + 1] == '.') j++
                    j
                } else if (isAsciiPeriodSentenceEnd(text, start)) {
                    start
                } else {
                    null
                }
            }
            else -> null
        } ?: return null

        while (end + 1 < text.length && text[end + 1] in closers) {
            end++
        }
        return end
    }

    private fun isAsciiPeriodSentenceEnd(text: String, index: Int): Boolean {
        val prev = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (prev == '.' || next == '.') return false
        if (next?.isDigit() == true) return false
        if (isCommonAbbreviation(text, index)) return false

        // Initials / "U.S." — single capital immediately before the dot
        if (prev != null && prev.isLetter() && prev.isUpperCase()) {
            val prev2 = text.getOrNull(index - 2)
            if (prev2 == null || !prev2.isLetter()) {
                if (next != null && next.isLetter() && next.isUpperCase()) {
                    return false
                }
                if (next == ' ') {
                    val afterSpace = text.getOrNull(index + 2)
                    if (afterSpace != null && afterSpace.isLetter() && afterSpace.isLowerCase()) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun isCommonAbbreviation(text: String, periodIndex: Int): Boolean {
        var start = periodIndex - 1
        while (start >= 0 && text[start].isLetter()) start--
        start++
        if (start >= periodIndex) return false
        return text.substring(start, periodIndex).lowercase() in abbreviations
    }

    private val NEWLINE_RUN = Regex("\n+")
}

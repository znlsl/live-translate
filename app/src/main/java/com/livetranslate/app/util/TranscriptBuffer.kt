package com.livetranslate.app.util

/**
 * Merges streaming transcript chunks from Live Translate.
 *
 * The server sometimes rewrites the whole string so far, and sometimes sends
 * only the next fragment. English fragments need a joining space ("hello"+"world");
 * CJK characters must not get one ("做"+"成" → "做成", not "做 成").
 */
object TranscriptBuffer {
    fun append(buffer: StringBuilder, chunk: String, maxChars: Int) {
        if (chunk.isEmpty()) return
        if (chunk.length >= buffer.length && chunk.startsWith(buffer)) {
            buffer.clear()
            buffer.append(chunk)
        } else if (buffer.endsWith(chunk)) {
            // ignore duplicate tail
        } else {
            if (shouldInsertJoinSpace(buffer, chunk)) {
                buffer.append(' ')
            }
            buffer.append(chunk)
        }
        if (maxChars > 0 && buffer.length > maxChars) {
            var cut = buffer.length - maxChars
            if (cut < buffer.length && buffer[cut].isLowSurrogate()) cut++
            cut = alignCutToSentenceBoundary(buffer, cut)
            if (cut > 0) buffer.delete(0, cut)
        }
    }

    /**
     * Move the cut point forward past any partial sentence so truncation drops
     * whole sentences instead of splitting one mid-way — a mid-sentence cut
     * changes the laid-out line count mid-stream and makes the overlay jump.
     *
     * Looks for the next sentence terminator at or after [startCut] (within a
     * small window) and returns the index just after it. Falls back to the
     * original [startCut] if no terminator is found nearby, so the cap is
     * still respected.
     */
    internal fun alignCutToSentenceBoundary(buffer: CharSequence, startCut: Int): Int {
        if (startCut <= 0 || startCut >= buffer.length) return startCut
        val window = 64
        val limit = minOf(startCut + window, buffer.length)
        var i = startCut
        while (i < limit) {
            if (isSentenceEnd(buffer[i])) {
                var end = i + 1
                // skip trailing closing quotes/brackets
                while (end < buffer.length && buffer[end] in CLOSERS) end++
                // skip one joining space so the kept text starts cleanly
                if (end < buffer.length && buffer[end] == ' ') end++
                return end.coerceAtMost(buffer.length)
            }
            i++
        }
        return startCut
    }

    private fun isSentenceEnd(c: Char): Boolean =
        c == '。' || c == '．' || c == '！' || c == '？' ||
            c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

    private val CLOSERS = charArrayOf(
        '”', '’', '"', '\'',
        '」', '』', '）', ')', '】', '》', '>',
    )

    /**
     * Drops spaces that landed between two CJK letters (ours or the model's).
     * Leaves "hello world" and "用 hello" alone.
     */
    fun collapseCjkInteriorSpaces(text: String): String {
        if (text.length < 3) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isBreakableSpace(c)) {
                val prev = sb.lastOrNull()
                var j = i
                while (j < text.length && isBreakableSpace(text[j])) j++
                val next = text.getOrNull(j)
                if (prev != null && next != null && isCjkWriting(prev) && isCjkWriting(next)) {
                    i = j
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return if (sb.length == text.length) text else sb.toString()
    }

    internal fun shouldInsertJoinSpace(buffer: StringBuilder, chunk: String): Boolean {
        if (buffer.isEmpty() || chunk.isEmpty()) return false
        val left = buffer.last()
        val right = chunk.first()
        if (left.isWhitespace() || right.isWhitespace()) return false
        if (isCjkWriting(left) || isCjkWriting(right)) return false
        return true
    }

    internal fun isCjkWriting(c: Char): Boolean {
        val code = c.code
        return when {
            code == 0x3000 -> false // ideographic space — treat as space, not a letter
            code in 0x2E80..0x9FFF -> true
            code in 0xF900..0xFAFF -> true
            code in 0xFF00..0xFFEF -> true
            code in 0xAC00..0xD7AF -> true
            else -> false
        }
    }

    private fun isBreakableSpace(c: Char): Boolean =
        c == ' ' || c == '\u00A0' || c == '\u3000'
}

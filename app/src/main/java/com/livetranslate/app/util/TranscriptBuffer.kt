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
            if (cut > 0) buffer.delete(0, cut)
        }
    }

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

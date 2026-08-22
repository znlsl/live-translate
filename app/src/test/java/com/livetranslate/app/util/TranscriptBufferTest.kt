package com.livetranslate.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptBufferTest {

    @Test
    fun cjkFragmentsDoNotGetAJoiningSpace() {
        val buf = StringBuilder("把它做")
        TranscriptBuffer.append(buf, "成任何其他东西", maxChars = 800)
        assertEquals("把它做成任何其他东西", buf.toString())
    }

    @Test
    fun latinFragmentsStillGetAJoiningSpace() {
        val buf = StringBuilder("hello")
        TranscriptBuffer.append(buf, "world", maxChars = 800)
        assertEquals("hello world", buf.toString())
    }

    @Test
    fun mixedCjkThenLatinDoesNotInsertSpace() {
        val buf = StringBuilder("用")
        TranscriptBuffer.append(buf, "hello", maxChars = 800)
        assertEquals("用hello", buf.toString())
    }

    @Test
    fun cumulativeRewriteReplacesBuffer() {
        val buf = StringBuilder("把它做")
        TranscriptBuffer.append(buf, "把它做成任何其他东西", maxChars = 800)
        assertEquals("把它做成任何其他东西", buf.toString())
    }

    @Test
    fun duplicateTailIsIgnored() {
        val buf = StringBuilder("把它做成")
        TranscriptBuffer.append(buf, "做成", maxChars = 800)
        assertEquals("把它做成", buf.toString())
    }

    @Test
    fun existingWhitespaceIsNotDoubled() {
        val buf = StringBuilder("hello ")
        TranscriptBuffer.append(buf, "world", maxChars = 800)
        assertEquals("hello world", buf.toString())
    }

    @Test
    fun collapseRemovesSpacesBetweenHan() {
        assertEquals("做成任何其他东西", TranscriptBuffer.collapseCjkInteriorSpaces("做 成任何其他东西"))
        assertEquals("做成", TranscriptBuffer.collapseCjkInteriorSpaces("做  成"))
        assertEquals("hello world", TranscriptBuffer.collapseCjkInteriorSpaces("hello world"))
        assertEquals("用 hello", TranscriptBuffer.collapseCjkInteriorSpaces("用 hello"))
    }

    @Test
    fun shouldInsertJoinSpaceMatrix() {
        assertFalse(TranscriptBuffer.shouldInsertJoinSpace(StringBuilder("做"), "成"))
        assertTrue(TranscriptBuffer.shouldInsertJoinSpace(StringBuilder("hello"), "world"))
        assertFalse(TranscriptBuffer.shouldInsertJoinSpace(StringBuilder("做"), "hello"))
        assertFalse(TranscriptBuffer.shouldInsertJoinSpace(StringBuilder("hello"), "做"))
    }

    @Test
    fun formatAlsoStripsCjkInteriorSpaces() {
        val clean = "好的。嗯。然后他就走了。后来我们去了公园。下一段从这里开始。"
        val gapped = "好的。嗯。然后他就走 了。后来我们去了公园。下一段从这里开始。"
        assertEquals(TranscriptLineBreaker.format(clean), TranscriptLineBreaker.format(gapped))
    }

    @Test
    fun truncationDropsWholeSentencesNotHalf() {
        val sentences = "这是第一句话。这是第二句话。这是第三句话。这是第四句话。"
        val buf = StringBuilder()
        // Fill past the cap so a truncation happens.
        TranscriptBuffer.append(buf, sentences + sentences, maxChars = 40)
        val kept = buf.toString()
        // Kept text must start at a sentence boundary, never mid-sentence.
        assertTrue(
            "expected kept text to start after a '。', was: \"$kept\"",
            kept.startsWith("这是") && kept.indexOf("这是第一句") != 0,
        )
        assertTrue(kept.length <= 40)
    }

    @Test
    fun alignCutToSentenceBoundaryMovesPastTerminator() {
        // Cut lands mid-sentence; should advance to just after the next '。'.
        val text = "你好这是一个完整句子。然后是下一句开始的部分"
        val cut = TranscriptBuffer.alignCutToSentenceBoundary(text, 4)
        assertEquals("你好这是一个完整句子。".length, cut)
    }

    @Test
    fun alignCutToSentenceBoundaryFallsBackWhenNoTerminatorNearby() {
        val text = "一段没有任何句号的长文本继续继续继续继续继续"
        val cut = TranscriptBuffer.alignCutToSentenceBoundary(text, 6)
        // No terminator within the window → unchanged.
        assertEquals(6, cut)
    }

    @Test
    fun truncationRespectsSurrogateBoundary() {
        // Emoji is a surrogate pair; truncation must not split it.
        val text = "😀😀😀😀😀😀😀😀这是一段话用于触发截断的填充内容。"
        val buf = StringBuilder()
        TranscriptBuffer.append(buf, text, maxChars = 12)
        // No lone high/low surrogate should remain at the head.
        val kept = buf.toString()
        assertFalse(kept.isNotEmpty() && kept[0].isHighSurrogate() && kept.length > 1 && !kept[1].isLowSurrogate())
    }
}

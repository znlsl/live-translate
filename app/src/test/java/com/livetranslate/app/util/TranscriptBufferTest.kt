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
}

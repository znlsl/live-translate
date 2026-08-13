package com.livetranslate.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptLineBreakerTest {

    @Test
    fun emptyAndBlankStayUnchanged() {
        assertEquals("", TranscriptLineBreaker.format(""))
        assertEquals("   ", TranscriptLineBreaker.format("   "))
    }

    @Test
    fun shortChineseSentencesDoNotBreak() {
        val src = "好的。嗯。然后他就走了。"
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun chineseBreaksOnceLineReachesThreshold() {
        val src = "好的。嗯。然后他就走了。后来我们去了公园。下一段从这里开始。"
        val expected = "好的。嗯。然后他就走了。后来我们去了公园。\n下一段从这里开始。"
        assertEquals(expected, TranscriptLineBreaker.format(src))
    }

    @Test
    fun noTrailingNewlineWhenTextEndsOnBreak() {
        val src = "好的。嗯。然后他就走了。后来我们去了公园。"
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun longTextWithoutPunctuationStaysOneLine() {
        val src = "这是一段没有任何句末标点的长文用来确认不会凭空插入换行符"
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun existingNewlinesAreKept() {
        val src = "短句。\n另一行继续写到足够的长度之后再遇到句号。后面还有一句。"
        val expected = "短句。\n另一行继续写到足够的长度之后再遇到句号。\n后面还有一句。"
        assertEquals(expected, TranscriptLineBreaker.format(src))
    }

    @Test
    fun thresholdBoundaryExactlyTwentyBreaks() {
        // 19 digits + period = 20 chars; more text follows → break
        val src = "1234567890123456789。下一句"
        assertEquals("1234567890123456789。\n下一句", TranscriptLineBreaker.format(src))
    }

    @Test
    fun thresholdBoundaryNineteenDoesNotBreak() {
        val src = "123456789012345678。X"
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun englishPeriodBreaksAndEatsFollowingSpace() {
        val src = "This is a complete sentence. And another one follows here."
        val expected = "This is a complete sentence.\nAnd another one follows here."
        assertEquals(expected, TranscriptLineBreaker.format(src))
    }

    @Test
    fun decimalPointIsNotABreak() {
        val src = "The value is 3.14 exactly and we keep going."
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun abbreviationMrIsNotABreak() {
        val src = "I met Mr. Smith at the station today."
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun ellipsisDoesNotFragmentShortLeadIn() {
        val src = "Wait... he actually left."
        assertEquals(src, TranscriptLineBreaker.format(src))
    }

    @Test
    fun questionAndExclamationCountAsSentenceEnds() {
        val src = "你真的要走吗？当然不是现在就立刻决定啊！后面还有别的安排。"
        val expected = "你真的要走吗？当然不是现在就立刻决定啊！\n后面还有别的安排。"
        assertEquals(expected, TranscriptLineBreaker.format(src))
    }

    @Test
    fun closingQuoteStaysWithPeriod() {
        val src = "他说：“好的。”然后我们就一起离开公园了。下句开始。"
        val expected = "他说：“好的。”然后我们就一起离开公园了。\n下句开始。"
        assertEquals(expected, TranscriptLineBreaker.format(src))
    }

    @Test
    fun trailingSpaceAfterLongSentenceDoesNotThrow() {
        val src = "1234567890123456789。 "
        assertEquals("1234567890123456789。 ", TranscriptLineBreaker.format(src))
    }

    @Test
    fun markdownUsesParagraphBreaks() {
        val src = "好的。嗯。然后他就走了。后来我们去了公园。下一段从这里开始。"
        val expected = "好的。嗯。然后他就走了。后来我们去了公园。\n\n下一段从这里开始。"
        assertEquals(expected, TranscriptLineBreaker.formatForMarkdown(src))
    }
}

package com.livetranslate.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedLanguagesTest {

    @Test
    fun officialTableHas78UniqueCodes() {
        val codes = SupportedLanguages.targetOptions.map { it.code }
        assertEquals(78, codes.size)
        assertEquals(78, codes.toSet().size)
    }

    @Test
    fun featuredLanguagesStayAtFront() {
        val codes = SupportedLanguages.targetOptions.map { it.code }
        assertEquals("zh-Hans", codes[0])
        assertEquals("zh-Hant", codes[1])
        assertEquals("en", codes[2])
        assertTrue(codes.contains("zu"))
        assertTrue(codes.contains("no"))
    }

    @Test
    fun canonicalizesNorwegianAliasesAndUnknowns() {
        assertEquals("no", SupportedLanguages.canonicalOrDefault("nb"))
        assertEquals("no", SupportedLanguages.canonicalOrDefault("NN"))
        assertEquals("zh-Hans", SupportedLanguages.canonicalOrDefault("zh-Hans"))
        assertEquals("zh-Hans", SupportedLanguages.canonicalOrDefault("auto"))
        assertEquals("zh-Hans", SupportedLanguages.canonicalOrDefault(""))
    }
}

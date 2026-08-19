package com.livetranslate.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCodesTest {

    @Test
    fun exactBcp47Matches() {
        assertTrue(LanguageCodes.matchesTarget("zh-Hans", "zh-Hans"))
        assertTrue(LanguageCodes.matchesTarget("en", "en"))
        assertTrue(LanguageCodes.matchesTarget("ja", "ja"))
    }

    @Test
    fun chineseAliasesMatchSimplified() {
        assertTrue(LanguageCodes.matchesTarget("zh", "zh-Hans"))
        assertTrue(LanguageCodes.matchesTarget("zh-CN", "zh-Hans"))
        assertTrue(LanguageCodes.matchesTarget("cmn", "zh-Hans"))
        assertTrue(LanguageCodes.matchesTarget("zh_hans", "zh-Hans"))
    }

    @Test
    fun simplifiedDoesNotMatchTraditional() {
        assertFalse(LanguageCodes.matchesTarget("zh-Hans", "zh-Hant"))
        assertFalse(LanguageCodes.matchesTarget("zh-CN", "zh-Hant"))
        assertFalse(LanguageCodes.matchesTarget("zh-TW", "zh-Hans"))
    }

    @Test
    fun traditionalAliasesMatch() {
        assertTrue(LanguageCodes.matchesTarget("zh-Hant", "zh-Hant"))
        assertTrue(LanguageCodes.matchesTarget("zh-TW", "zh-Hant"))
        assertTrue(LanguageCodes.matchesTarget("zh-HK", "zh-Hant"))
    }

    @Test
    fun norwegianAliasesMatch() {
        assertTrue(LanguageCodes.matchesTarget("nb", "no"))
        assertTrue(LanguageCodes.matchesTarget("nn", "no"))
        assertTrue(LanguageCodes.matchesTarget("no", "no"))
    }

    @Test
    fun differentLanguagesDoNotMatch() {
        assertFalse(LanguageCodes.matchesTarget("en", "zh-Hans"))
        assertFalse(LanguageCodes.matchesTarget("ja", "ko"))
        assertFalse(LanguageCodes.matchesTarget(null, "zh-Hans"))
        assertFalse(LanguageCodes.matchesTarget("", "zh-Hans"))
    }

    @Test
    fun hanTextLooksLikeSimplifiedChinese() {
        assertTrue(
            LanguageCodes.textLooksLikeTarget(
                "今天天气很好我们一起去公园走走吧",
                "zh-Hans",
            ),
        )
        assertFalse(
            LanguageCodes.textLooksLikeTarget(
                "Hello everyone this is an English sentence",
                "zh-Hans",
            ),
        )
    }

    @Test
    fun latinTextLooksLikeEnglishNotChinese() {
        assertTrue(
            LanguageCodes.textLooksLikeTarget(
                "Hello everyone this is an English sentence",
                "en",
            ),
        )
        assertFalse(
            LanguageCodes.textLooksLikeTarget(
                "今天天气很好我们一起去公园走走吧",
                "en",
            ),
        )
    }

    @Test
    fun chineseHanDoesNotCountAsJapaneseWithoutKana() {
        assertFalse(
            LanguageCodes.textLooksLikeTarget(
                "今天天气很好我们一起去公园走走吧",
                "ja",
            ),
        )
        assertTrue(
            LanguageCodes.textLooksLikeTarget(
                "こんにちは、今日はいい天気ですね",
                "ja",
            ),
        )
    }

    @Test
    fun japaneseWithKanaDoesNotLookLikeChinese() {
        assertFalse(
            LanguageCodes.textLooksLikeTarget(
                "こんにちは、今日はいい天気ですね私達は公園へ行きます",
                "zh-Hans",
            ),
        )
    }

    @Test
    fun shortTextIsNotGuessed() {
        assertFalse(LanguageCodes.textLooksLikeTarget("你好啊", "zh-Hans"))
    }
}

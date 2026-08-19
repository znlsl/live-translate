package com.livetranslate.app.data

import androidx.annotation.StringRes
import com.livetranslate.app.R

data class LanguageOption(
    val code: String,
    @StringRes val labelRes: Int,
)

/**
 * Target languages from the official Live Translate table
 * (https://ai.google.dev/gemini-api/docs/live-api/live-translate#supported-languages).
 *
 * The API has no source-language field — input is auto-detected.
 */
object SupportedLanguages {
    /**
     * Official BCP-47 codes. Norwegian is listed as "no, nb"; we send `no`.
     */
    private val official: List<LanguageOption> = listOf(
        LanguageOption("af", R.string.lang_af),
        LanguageOption("ak", R.string.lang_ak),
        LanguageOption("sq", R.string.lang_sq),
        LanguageOption("am", R.string.lang_am),
        LanguageOption("ar", R.string.lang_ar),
        LanguageOption("hy", R.string.lang_hy),
        LanguageOption("az", R.string.lang_az),
        LanguageOption("eu", R.string.lang_eu),
        LanguageOption("be", R.string.lang_be),
        LanguageOption("bn", R.string.lang_bn),
        LanguageOption("bg", R.string.lang_bg),
        LanguageOption("my", R.string.lang_my),
        LanguageOption("ca", R.string.lang_ca),
        LanguageOption("zh-Hans", R.string.lang_zh_hans),
        LanguageOption("zh-Hant", R.string.lang_zh_hant),
        LanguageOption("hr", R.string.lang_hr),
        LanguageOption("cs", R.string.lang_cs),
        LanguageOption("da", R.string.lang_da),
        LanguageOption("nl", R.string.lang_nl),
        LanguageOption("en", R.string.lang_en),
        LanguageOption("et", R.string.lang_et),
        LanguageOption("fil", R.string.lang_fil),
        LanguageOption("fi", R.string.lang_fi),
        LanguageOption("fr", R.string.lang_fr),
        LanguageOption("gl", R.string.lang_gl),
        LanguageOption("ka", R.string.lang_ka),
        LanguageOption("de", R.string.lang_de),
        LanguageOption("el", R.string.lang_el),
        LanguageOption("gu", R.string.lang_gu),
        LanguageOption("ha", R.string.lang_ha),
        LanguageOption("he", R.string.lang_he),
        LanguageOption("hi", R.string.lang_hi),
        LanguageOption("hu", R.string.lang_hu),
        LanguageOption("is", R.string.lang_is),
        LanguageOption("id", R.string.lang_id),
        LanguageOption("it", R.string.lang_it),
        LanguageOption("ja", R.string.lang_ja),
        LanguageOption("jv", R.string.lang_jv),
        LanguageOption("kn", R.string.lang_kn),
        LanguageOption("kk", R.string.lang_kk),
        LanguageOption("km", R.string.lang_km),
        LanguageOption("rw", R.string.lang_rw),
        LanguageOption("ko", R.string.lang_ko),
        LanguageOption("lo", R.string.lang_lo),
        LanguageOption("lv", R.string.lang_lv),
        LanguageOption("lt", R.string.lang_lt),
        LanguageOption("mk", R.string.lang_mk),
        LanguageOption("ms", R.string.lang_ms),
        LanguageOption("ml", R.string.lang_ml),
        LanguageOption("mr", R.string.lang_mr),
        LanguageOption("mn", R.string.lang_mn),
        LanguageOption("ne", R.string.lang_ne),
        LanguageOption("no", R.string.lang_no),
        LanguageOption("fa", R.string.lang_fa),
        LanguageOption("pl", R.string.lang_pl),
        LanguageOption("pt-BR", R.string.lang_pt_br),
        LanguageOption("pt-PT", R.string.lang_pt_pt),
        LanguageOption("pa", R.string.lang_pa),
        LanguageOption("ro", R.string.lang_ro),
        LanguageOption("ru", R.string.lang_ru),
        LanguageOption("sr", R.string.lang_sr),
        LanguageOption("sd", R.string.lang_sd),
        LanguageOption("si", R.string.lang_si),
        LanguageOption("sk", R.string.lang_sk),
        LanguageOption("sl", R.string.lang_sl),
        LanguageOption("es", R.string.lang_es),
        LanguageOption("su", R.string.lang_su),
        LanguageOption("sw", R.string.lang_sw),
        LanguageOption("sv", R.string.lang_sv),
        LanguageOption("ta", R.string.lang_ta),
        LanguageOption("te", R.string.lang_te),
        LanguageOption("th", R.string.lang_th),
        LanguageOption("tr", R.string.lang_tr),
        LanguageOption("uk", R.string.lang_uk),
        LanguageOption("ur", R.string.lang_ur),
        LanguageOption("uz", R.string.lang_uz),
        LanguageOption("vi", R.string.lang_vi),
        LanguageOption("zu", R.string.lang_zu),
    )

    private val byCode: Map<String, LanguageOption> =
        official.associateBy { it.code.lowercase() }

    /** Common picks first, then the rest of the official table. */
    private val featuredCodes = listOf(
        "zh-Hans", "zh-Hant", "en", "ja", "ko",
        "es", "fr", "de", "ru", "pt-BR", "pt-PT",
        "it", "ar", "hi", "th", "vi", "id", "tr", "pl", "nl", "uk",
    )

    private val featuredSet = featuredCodes.map { it.lowercase() }.toSet()

    val targetOptions: List<LanguageOption> = buildList {
        featuredCodes.forEach { code ->
            byCode[code.lowercase()]?.let { add(it) }
        }
        official.filter { it.code.lowercase() !in featuredSet }.forEach { add(it) }
    }

    fun labelResOf(code: String): Int =
        byCode[code.lowercase()]?.labelRes ?: R.string.lang_zh_hans

    fun canonicalOrDefault(code: String): String {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return UserSettings.Defaults.TARGET_LANGUAGE
        val lower = trimmed.lowercase()
        if (lower == "nb" || lower == "nn") return "no"
        return byCode[lower]?.code ?: UserSettings.Defaults.TARGET_LANGUAGE
    }
}

package com.livetranslate.app.data

/**
 * All user-facing settings that are safe to put in DataStore (not the API key).
 *
 * Live Translate has no source-language field; only [targetLanguageCode] is sent.
 */
data class UserSettings(
    val endpoint: String = Defaults.ENDPOINT,
    val modelId: String = Defaults.MODEL_ID,
    val targetLanguageCode: String = Defaults.TARGET_LANGUAGE,
    val fontSizeSp: Float = Defaults.FONT_SIZE_SP,
    val backgroundAlpha: Float = Defaults.BACKGROUND_ALPHA,
    val bilingual: Boolean = Defaults.BILINGUAL,
    val playTranslatedAudio: Boolean = Defaults.PLAY_TRANSLATED_AUDIO,
    val translatedVolume: Float = Defaults.TRANSLATED_VOLUME,
    val overlayX: Int = Defaults.OVERLAY_X,
    val overlayY: Int = Defaults.OVERLAY_Y,
    val overlayWidthDp: Int = Defaults.OVERLAY_WIDTH_DP,
    val overlayHeightDp: Int = Defaults.OVERLAY_HEIGHT_DP,
    val audioSourceMode: AudioSourceMode = Defaults.AUDIO_SOURCE,
) {
    object Defaults {
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val MODEL_ID = "gemini-3.5-live-translate-preview"
        const val TARGET_LANGUAGE = "zh-Hans"
        const val FONT_SIZE_SP = 18f
        const val BACKGROUND_ALPHA = 0.65f
        const val BILINGUAL = false
        const val PLAY_TRANSLATED_AUDIO = false
        const val TRANSLATED_VOLUME = 0.8f
        const val OVERLAY_X = 24
        const val OVERLAY_Y = -1
        const val OVERLAY_WIDTH_DP = 360
        const val OVERLAY_HEIGHT_DP = 120
        val AUDIO_SOURCE: AudioSourceMode = AudioSourceMode.MEDIA
    }
}

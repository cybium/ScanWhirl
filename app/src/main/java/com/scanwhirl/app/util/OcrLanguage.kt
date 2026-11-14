package com.scanwhirl.app.util

/** Which recognizer(s) the user wants (design §3: "Hindi text", Text screen language chip). */
enum class OcrLanguage(val prefValue: String, val scripts: List<OcrScript>) {
    ENGLISH("en", listOf(OcrScript.LATIN)),
    ENGLISH_HINDI("en+hi", listOf(OcrScript.LATIN, OcrScript.DEVANAGARI)),
    HINDI("hi", listOf(OcrScript.DEVANAGARI));

    companion object {
        fun fromPref(value: String?): OcrLanguage =
            entries.firstOrNull { it.prefValue == value } ?: ENGLISH
    }
}

enum class OcrScript { LATIN, DEVANAGARI }

/** Small settings accessor; the value lives in [Prefs] so it survives the process. */
object OcrLanguageSettings {

    fun get(anyContext: android.content.Context): OcrLanguage =
        OcrLanguage.fromPref(Prefs.ocrLanguage(anyContext.applicationContext))

    fun set(anyContext: android.content.Context, language: OcrLanguage) {
        Prefs.setOcrLanguage(anyContext.applicationContext, language.prefValue)
    }
}

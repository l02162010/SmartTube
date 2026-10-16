package com.liskovsoft.smartyoutubetv2.common.dualsub

import java.util.Locale

object LangUtil {
    private val TRADITIONAL_MARKERS = setOf("hant", "tw", "hk", "mo")
    /** Common characters that only exist in Simplified Chinese (their Traditional forms differ) */
    private const val SIMPLIFIED_ONLY = "们这为说时会对过还没问吗让从发东应该经长开关门见现实间点头体样气报级认语题谁动边当国学么个来话们觉还员钱电视听写读请"

    /**
     * Drops the region code (en-US -> en), but keeps the Chinese script apart
     * (zh-TW -> zh-Hant, zh-CN -> zh-Hans).
     */
    @JvmStatic
    fun normalize(code: String?): String {
        if (code.isNullOrBlank()) {
            return ""
        }

        val parts = code.trim().replace('_', '-').split('-').map { it.lowercase(Locale.ROOT) }
        val base = parts[0]

        if (base == "zh") {
            return if (parts.drop(1).any { it in TRADITIONAL_MARKERS }) "zh-Hant" else "zh-Hans"
        }

        return base
    }

    /** Written-Cantonese characters that never appear in Taiwanese Mandarin */
    private const val CANTONESE_ONLY = "嘅咗唔嘢啲冇喺嚟佢哋"

    @JvmStatic
    fun hasCantoneseChars(text: String): Boolean = text.any { CANTONESE_ONLY.indexOf(it) != -1 }

    @JvmStatic
    fun hasSimplifiedOnlyChars(text: String): Boolean = text.any { SIMPLIFIED_ONLY.indexOf(it) != -1 }

    /**
     * Same language for reading purposes: Chinese scripts count as one language (zh-TW == zh-CN).
     * Used to skip dual subtitles when the viewer can already read the original.
     */
    @JvmStatic
    fun isSameLanguage(a: String?, b: String?): Boolean {
        val na = normalize(a).substringBefore('-')
        return na.isNotEmpty() && na == normalize(b).substringBefore('-')
    }

    /**
     * Checks the subtitle text itself, because uploaders put anything under any language tag
     * (e.g. pinyin + Chinese + Arabic in an "ar" track). Only for Chinese targets: true when at least
     * half of the lines contain Han characters and no kana/hangul (so Japanese and Korean don't count).
     */
    @JvmStatic
    fun isTextReadableAs(lines: List<String>, targetLang: String?): Boolean {
        if (lines.isEmpty() || normalize(targetLang).substringBefore('-') != "zh") {
            return false
        }

        val chinese = lines.count { line ->
            line.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } &&
                line.none {
                    val script = Character.UnicodeScript.of(it.code)
                    script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA ||
                        script == Character.UnicodeScript.HANGUL
                }
        }

        return chinese * 2 >= lines.size
    }

    /**
     * True when the text is written in the script of [lang] (e.g. an "ar" track must be mostly Arabic letters).
     * Catches mislabeled tracks and language-learning tracks that stack several languages on one cue.
     */
    @JvmStatic
    fun matchesScript(lines: List<String>, lang: String?): Boolean {
        val expected = expectedScripts(lang)
        var letters = 0
        var matching = 0

        for (line in lines) {
            for (c in line) {
                if (!Character.isLetter(c)) {
                    continue
                }
                letters++
                if (Character.UnicodeScript.of(c.code) in expected) {
                    matching++
                }
            }
        }

        return letters == 0 || matching >= letters * 0.85
    }

    private fun expectedScripts(lang: String?): Set<Character.UnicodeScript> {
        val s = Character.UnicodeScript::class.java.enumConstants.associateBy { it.name }
        fun of(vararg names: String) = names.mapNotNull { s[it] }.toSet()
        return when (normalize(lang).substringBefore('-')) {
            "zh" -> of("HAN")
            "ja" -> of("HAN", "HIRAGANA", "KATAKANA")
            "ko" -> of("HANGUL", "HAN")
            "ar", "fa", "ur", "ps", "ckb" -> of("ARABIC")
            "iw", "he", "yi" -> of("HEBREW")
            "ru", "uk", "bg", "sr", "be", "kk", "mk", "mn", "ky", "tg" -> of("CYRILLIC")
            "el" -> of("GREEK")
            "th" -> of("THAI")
            "hi", "mr", "ne" -> of("DEVANAGARI")
            "bn", "as" -> of("BENGALI")
            "ta" -> of("TAMIL")
            "my" -> of("MYANMAR")
            "km" -> of("KHMER")
            "lo" -> of("LAO")
            "hy" -> of("ARMENIAN")
            "ka" -> of("GEORGIAN")
            else -> of("LATIN")
        }
    }

    @JvmStatic
    fun isSame(a: String?, b: String?): Boolean {
        val na = normalize(a)
        return na.isNotEmpty() && na == normalize(b)
    }
}

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

    @JvmStatic
    fun isSame(a: String?, b: String?): Boolean {
        val na = normalize(a)
        return na.isNotEmpty() && na == normalize(b)
    }
}

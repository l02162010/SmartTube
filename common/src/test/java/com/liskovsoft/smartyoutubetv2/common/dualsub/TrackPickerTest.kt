package com.liskovsoft.smartyoutubetv2.common.dualsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPickerTest {
    private val base = "https://www.youtube.com/api/timedtext?v=abc&fmt=vtt"
    private val enAuto = SubTrack("en", "$base&kind=asr&lang=en", true)
    private val enManual = SubTrack("en-US", "$base&lang=en-US", false)
    private val jaManual = SubTrack("ja", "$base&lang=ja", false)
    private val zhTranslated = SubTrack("zh-Hant", "$base&kind=asr&lang=en&tlang=zh-Hant", true)

    @Test
    fun languageMatching() {
        assertTrue(LangUtil.isSame("en", "en-US"))
        assertTrue(LangUtil.isSame("zh-TW", "zh-Hant"))
        assertTrue(LangUtil.isSame("zh-HK", "zh-Hant"))
        assertTrue(LangUtil.isSame("zh-CN", "zh-Hans"))
        assertTrue(LangUtil.isSame("zh", "zh-Hans"))
        assertFalse(LangUtil.isSame("zh-TW", "zh-CN"))
        assertFalse(LangUtil.isSame("", ""))

        // Readable without translation: any Chinese script, any region
        assertTrue(LangUtil.isSameLanguage("zh-CN", "zh-Hant"))
        assertTrue(LangUtil.isSameLanguage("zh-TW", "zh-Hant"))
        assertTrue(LangUtil.isSameLanguage("en-GB", "en"))
        assertFalse(LangUtil.isSameLanguage("ja", "zh-Hant"))
        assertFalse(LangUtil.isSameLanguage("", ""))
    }

    @Test
    fun translatedTracksAreNeverPicked() {
        assertNull(TrackPicker.pick(listOf(zhTranslated), null))
        assertNull(TrackPicker.pick(listOf(zhTranslated), "zh-Hant"))
    }

    @Test
    fun manualInAutoLanguageWins() {
        assertEquals(enManual, TrackPicker.pick(listOf(jaManual, enAuto, enManual, zhTranslated), null))
    }

    @Test
    fun autoBeforeOtherManual() {
        assertEquals(enAuto, TrackPicker.pick(listOf(jaManual, enAuto), null))
    }

    @Test
    fun englishBeforeOtherManualWithoutAuto() {
        assertEquals(enManual, TrackPicker.pick(listOf(jaManual, enManual), null))
        assertEquals(jaManual, TrackPicker.pick(listOf(jaManual), null))
    }

    @Test
    fun preferredLanguage() {
        assertEquals(jaManual, TrackPicker.pick(listOf(enAuto, enManual, jaManual), "ja"))
        assertEquals(enManual, TrackPicker.pick(listOf(enAuto, enManual), "en"))
        // Not available: falls back to auto rules
        assertEquals(enManual, TrackPicker.pick(listOf(enAuto, enManual), "ko"))
    }

    @Test
    fun originalAudioLanguageWins() {
        // Chinese cooking video: no real ASR, English translation listed first
        val enUs = SubTrack("en-US", "$base&lang=en-US", false)
        val zhHans = SubTrack("zh-Hans", "$base&lang=zh-Hans", false)
        val zhHant = SubTrack("zh-Hant", "$base&lang=zh-Hant", false)
        val dubAsr = SubTrack("en", "$base&kind=asr&lang=en&variant=timing-optimized", true)
        val tracks = listOf(enUs, dubAsr, zhHans, zhHant)

        assertEquals(enUs, TrackPicker.pick(tracks, null)) // old rule: first manual
        assertEquals(zhHant, TrackPicker.pick(tracks, null, "zh-Hant"))
        // The user's source setting still wins
        assertEquals(enUs, TrackPicker.pick(tracks, "en", "zh-Hant"))
        // Unknown audio language: same as before
        assertEquals(enUs, TrackPicker.pick(tracks, null, "ko"))
    }

    @Test
    fun noAutoTrackAndUnknownAudio() {
        // Chinese vlog with many uploaded translations, Arabic listed first
        val ar = SubTrack("ar", "$base&lang=ar", false)
        val en = SubTrack("en", "$base&lang=en", false)
        val zhCn = SubTrack("zh-CN", "$base&lang=zh-CN", false)

        // A track the viewer reads wins (the controller then skips dual subs)
        assertEquals(zhCn, TrackPicker.pick(listOf(ar, en, zhCn), null, null, "zh-Hant"))
        // Otherwise English rather than whatever comes first
        assertEquals(en, TrackPicker.pick(listOf(ar, en), null, null, "zh-Hant"))
        assertEquals(ar, TrackPicker.pick(listOf(ar), null, null, "zh-Hant"))
    }

    @Test
    fun json3Url() {
        assertEquals("https://x/t?a=1&fmt=json3&b=2", DualSubSession.toJson3Url("https://x/t?a=1&fmt=vtt&b=2"))
        assertEquals("https://x/t?a=1&fmt=json3", DualSubSession.toJson3Url("https://x/t?a=1"))
    }
}

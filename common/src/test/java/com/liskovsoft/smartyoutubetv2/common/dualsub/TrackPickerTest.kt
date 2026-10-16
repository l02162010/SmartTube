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
    fun firstManualWithoutAuto() {
        assertEquals(jaManual, TrackPicker.pick(listOf(jaManual, enManual), null))
    }

    @Test
    fun preferredLanguage() {
        assertEquals(jaManual, TrackPicker.pick(listOf(enAuto, enManual, jaManual), "ja"))
        assertEquals(enManual, TrackPicker.pick(listOf(enAuto, enManual), "en"))
        // Not available: falls back to auto rules
        assertEquals(enManual, TrackPicker.pick(listOf(enAuto, enManual), "ko"))
    }

    @Test
    fun json3Url() {
        assertEquals("https://x/t?a=1&fmt=json3&b=2", DualSubSession.toJson3Url("https://x/t?a=1&fmt=vtt&b=2"))
        assertEquals("https://x/t?a=1&fmt=json3", DualSubSession.toJson3Url("https://x/t?a=1"))
    }
}

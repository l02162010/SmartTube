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
    fun scriptCheck() {
        // The "ar" track of a Chinese learning vlog: pinyin + Chinese + Arabic on one cue
        val mixed = listOf("zhèi yàng de huà 这样的话就能省很多时间 بهذه الطريقة يمكنني توفير الكثير من الوقت")
        assertFalse(LangUtil.matchesScript(mixed, "ar"))
        assertTrue(LangUtil.matchesScript(listOf("بهذه الطريقة يمكنني توفير الكثير من الوقت"), "ar"))
        // Normal tracks pass, a few foreign names are fine
        assertTrue(LangUtil.matchesScript(listOf("And then we went to Tokyo with Jia Jiang."), "en"))
        assertTrue(LangUtil.matchesScript(listOf("僕ね12年前にメキシコに来ました", "ポッドキャスト"), "ja"))
        assertTrue(LangUtil.matchesScript(listOf("Привет, как дела?"), "ru"))
        // Mislabeled: Chinese text in an English track
        assertFalse(LangUtil.matchesScript(listOf("我每天早上六點起床", "然後去上班"), "en"))
    }

    @Test
    fun textReadability() {
        // The "ar" track of a Chinese learning vlog
        val mixed = listOf("zhèi yàng de huà 这样的话就能省很多时间 بهذه الطريقة", "wǒ měi tiān 我每天早上 أنا كل يوم")
        assertTrue(LangUtil.isTextReadableAs(mixed, "zh-Hant"))
        assertFalse(LangUtil.isTextReadableAs(mixed, "en"))
        // Japanese has kanji too, but kana tells it apart
        assertFalse(LangUtil.isTextReadableAs(listOf("僕ね12年前にメキシコに来ました", "日本語が分かる"), "zh-Hant"))
        assertFalse(LangUtil.isTextReadableAs(listOf("Hello there", "General Kenobi"), "zh-Hant"))
        assertFalse(LangUtil.isTextReadableAs(emptyList(), "zh-Hant"))
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
    fun unknownSpokenLanguageGivesNothing() {
        // No ASR and no audio info: any of these could be an uploaded translation
        assertNull(TrackPicker.pick(listOf(jaManual, enManual), null))
        // ...but a single track is the transcript (e.g. a podcast with uploaded English subs only)
        assertEquals(enManual, TrackPicker.pick(listOf(enManual), null))
        assertEquals(jaManual, TrackPicker.pick(listOf(jaManual, enManual), null, "ja"))
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

        assertNull(TrackPicker.pick(tracks, null)) // spoken language unknown
        assertEquals(zhHant, TrackPicker.pick(tracks, null, "zh-Hant"))
        // The user's source setting still wins
        assertEquals(enUs, TrackPicker.pick(tracks, "en", "zh-Hant"))
        // Spoken language without subtitles: nothing
        assertNull(TrackPicker.pick(tracks, null, "ko"))
    }

    @Test
    fun noAutoTrackAndUnknownAudio() {
        // Chinese vlog with many uploaded translations, Arabic listed first
        val ar = SubTrack("ar", "$base&lang=ar", false)
        val en = SubTrack("en", "$base&lang=en", false)
        val zhCn = SubTrack("zh-CN", "$base&lang=zh-CN", false)

        // Never guess
        assertNull(TrackPicker.pick(listOf(ar, en, zhCn), null))
        assertEquals(ar, TrackPicker.pick(listOf(ar), null)) // single track: used, the script check guards it
        // Known spoken language: its track
        assertEquals(zhCn, TrackPicker.pick(listOf(ar, en, zhCn), null, "zh-CN"))
    }

    @Test
    fun json3Url() {
        assertEquals("https://x/t?a=1&fmt=json3&b=2", DualSubSession.toJson3Url("https://x/t?a=1&fmt=vtt&b=2"))
        assertEquals("https://x/t?a=1&fmt=json3", DualSubSession.toJson3Url("https://x/t?a=1"))
    }
}

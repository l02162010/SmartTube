package com.liskovsoft.smartyoutubetv2.common.dualsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HumanTranslationTest {
    @Test
    fun sameTimingPairsOneToOne() {
        val original = listOf(SubCue(0, 1000, "a"), SubCue(1000, 2000, "b"))
        val target = listOf(SubCue(0, 1000, "甲"), SubCue(1000, 2000, "乙"))

        assertEquals(listOf("甲", "乙"), alignTranslations(original, target))
    }

    @Test
    fun differentTimingIsAlignedByOverlap() {
        val original = listOf(SubCue(0, 2000, "a b"), SubCue(2000, 4000, "c"), SubCue(9000, 10000, "d"))
        val target = listOf(
            SubCue(100, 900, "甲"), SubCue(900, 2100, "乙"), // two target cues inside one original cue
            SubCue(2100, 3900, "丙"),                          // slightly shifted
            SubCue(5000, 6000, "丁")                           // no original cue there
        )

        assertEquals(listOf("甲 乙", "丙", null), alignTranslations(original, target))
    }

    @Test
    fun oneTargetCueSpanningTwoOriginals() {
        val original = listOf(SubCue(0, 1000, "a"), SubCue(1000, 2000, "b"))
        val target = listOf(SubCue(0, 2000, "甲乙"))

        assertEquals(listOf("甲乙", "甲乙"), alignTranslations(original, target))
    }

    @Test
    fun picksManualTrackInTargetLanguageOnly() {
        val base = "https://www.youtube.com/api/timedtext?v=abc"
        val tracks = listOf(
            SubTrack("en", "$base&lang=en", false),
            SubTrack("zh-Hant", "$base&lang=en&tlang=zh-Hant", true), // machine translation
            SubTrack("zh-CN", "$base&lang=zh-CN", false),
            SubTrack("zh-TW", "$base&lang=zh-TW", false)
        )

        assertEquals("zh-TW", TrackPicker.pickHumanTranslation(tracks, "zh-Hant")?.languageCode)
        assertEquals("zh-CN", TrackPicker.pickHumanTranslation(tracks, "zh-Hans")?.languageCode)
        assertNull(TrackPicker.pickHumanTranslation(tracks, "ja"))
    }

    @Test
    fun sessionUsesHumanTrackWithoutCallingClaude() {
        val original = """{"events":[{"tStartMs":0,"dDurationMs":1000,"segs":[{"utf8":"hello"}]}]}"""
        val chinese = """{"events":[{"tStartMs":50,"dDurationMs":900,"segs":[{"utf8":"你好"}]}]}"""
        val http = object : HttpTransport {
            var posts = 0
            override fun get(url: String) = HttpResult(200, if ("zh-TW" in url) chinese else original)
            override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResult { posts++; return HttpResult(500, "") }
        }
        val ready = CountDownLatch(1)
        val session = DualSubSession(
            SubTrack("en", "https://x/t?lang=en", false), http, null, null, null,
            object : DualSubSession.Listener {
                override fun onCuesReady() = ready.countDown()
                override fun onTranslated() {}
                override fun onError(error: Throwable, fatal: Boolean) {}
            },
            humanTranslationTrack = SubTrack("zh-TW", "https://x/t?lang=zh-TW", false)
        )

        session.start(0)

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals("hello", session.original(0))
        assertEquals("你好", session.translation(0))
        assertEquals(0, http.posts)
    }
}

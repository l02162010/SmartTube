package com.liskovsoft.smartyoutubetv2.common.dualsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DualSubSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val track = SubTrack("en", "https://www.youtube.com/api/timedtext?v=abc&fmt=vtt", false)

    /** n manual cues, one per second */
    private fun manualJson(n: Int): String {
        val events = (0 until n).joinToString(",") { i -> """{"tStartMs":${i * 1000},"dDurationMs":900,"segs":[{"utf8":"line$i"}]}""" }
        return """{"events":[$events]}"""
    }

    private class Waiter : DualSubSession.Listener {
        val ready = CountDownLatch(1)
        val errors = ArrayList<Throwable>()
        override fun onCuesReady() = ready.countDown()
        override fun onTranslated() {}
        override fun onError(error: Throwable, fatal: Boolean) { synchronized(errors) { errors.add(error) } }
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            assertTrue("timeout", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    @Test
    fun translatesFromPlaybackPositionAndCaches() {
        val http = FakeClaude().apply { subtitleBody = manualJson(200) }
        val cache = TranslationCache(tmp.root)
        val name = TranslationCache.fileName("abc", "en", "zh-Hant", ClaudeTranslator.MODEL_HAIKU)
        val translator = ClaudeTranslator(http, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant")
        val listener = Waiter()
        val session = DualSubSession(track, http, translator, cache, name, listener)

        session.start(125_000) // inside cue 125

        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        awaitTrue { (125 until 200).all { session.translation(it) != null } }

        // A small batch right at the position first, then full batches
        assertEquals(listOf("line125") + (126 until 135).map { "line$it" }, http.requests[0])
        assertTrue(http.requests.any { it.size == DualSubSession.BATCH_SIZE })
        assertEquals("line125-tr", session.translation(125))
        assertEquals("line125", session.original(session.findIndex(125_100)))
        // Lines already behind the position are not paid for
        assertTrue((0 until 125).all { session.translation(it) == null })
        assertTrue(listener.errors.isEmpty())

        // Saved once idle; second open at the same spot: straight from the cache, no requests
        awaitTrue { cache.read(name)?.translations?.count { it != null } == 75 }
        val http2 = FakeClaude()
        val listener2 = Waiter()
        val session2 = DualSubSession(track, http2, ClaudeTranslator(http2, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), cache, name, listener2)
        session2.start(125_000)
        assertTrue(listener2.ready.await(5, TimeUnit.SECONDS))
        assertEquals("line130-tr", session2.translation(130))
        Thread.sleep(300)
        assertTrue(http2.requests.isEmpty())
        session2.cancel()
    }

    @Test
    fun onlyTranslatesAheadOfThePosition() {
        // 2000 lines, one per second (33 minutes)
        val http = FakeClaude().apply { subtitleBody = manualJson(2000) }
        val translator = ClaudeTranslator(http, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant")
        val listener = Waiter()
        val session = DualSubSession(track, http, translator, null, null, listener)
        val lookaheadLines = (DualSubSession.LOOKAHEAD_MS / 1000).toInt()

        session.start(0)
        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        awaitTrue { session.translation(lookaheadLines - 1) != null }
        Thread.sleep(300)

        // Nothing far beyond the 5 minute window
        val translated = (0 until 2000).count { session.translation(it) != null }
        assertTrue("translated $translated", translated <= lookaheadLines + DualSubSession.BATCH_SIZE)
        assertTrue((1000 until 2000).all { session.translation(it) == null })

        // Seeking far ahead moves the window there
        session.setPositionMs(1_500_000)
        awaitTrue { session.translation(1500) != null && session.translation(1700) != null }
        assertTrue((700 until 1400).all { session.translation(it) == null })
        assertTrue(session.tokenUsage.first > 0)
        session.cancel()
    }

    @Test
    fun resumesWhatWasLeftUntranslated() {
        val cache = TranslationCache(tmp.root)
        val name = "resume.json"

        // First visit: batches touching line 60+ fail (e.g. overloaded API): [50,90) and [90,100) stay empty
        val failing = FakeClaude { _, lines -> if (lines.any { it.removePrefix("line").toInt() >= 60 }) HttpResult(500, "{}") else null }
            .apply { subtitleBody = manualJson(100) }
        val first = Waiter()
        val finished = CountDownLatch(1)
        DualSubSession(track, failing, ClaudeTranslator(failing, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant") {}, cache, name,
            object : DualSubSession.Listener by first {
                override fun onFinished(stats: String) = finished.countDown()
            }).start(0)
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals(50, cache.read(name)!!.translations.count { it != null })

        // Second visit: only the missing lines are requested
        val http = FakeClaude()
        val second = Waiter()
        val session = DualSubSession(track, http, ClaudeTranslator(http, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), cache, name, second)
        session.start(0)
        awaitTrue { session.isReady && (0 until 100).all { session.translation(it) != null } }

        assertTrue(http.requests.flatten().all { it.removePrefix("line").toInt() >= 50 })
        assertEquals(50, http.requests.flatten().size)
        assertEquals("line10-tr", session.translation(10))
    }

    @Test
    fun leavingEarlyKeepsWhatIsTranslated() {
        val cache = TranslationCache(tmp.root)
        // Only the quick batch at the position answers, everything else hangs until cancel
        val gate = CountDownLatch(1)
        val http = FakeClaude { _, lines -> if (lines[0] != "line50") gate.await() ; null }.apply { subtitleBody = manualJson(200) }
        val listener = Waiter()
        val session = DualSubSession(track, http, ClaudeTranslator(http, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), cache, "early.json", listener)

        session.start(50_000)
        awaitTrue { session.isReady && session.translation(50) != null }
        session.cancel()
        gate.countDown()

        awaitTrue { cache.read("early.json") != null }
        val saved = cache.read("early.json")!!.translations
        assertEquals("line50-tr", saved[50])
        assertEquals(DualSubSession.QUICK_BATCH_SIZE, saved.count { it != null })
    }

    @Test
    fun authErrorKeepsOriginalAndSkipsCache() {
        val http = FakeClaude { _, _ -> HttpResult(401, "{}") }.apply { subtitleBody = manualJson(100) }
        val cache = TranslationCache(tmp.root)
        val listener = Waiter()
        val session = DualSubSession(track, http, ClaudeTranslator(http, "bad", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), cache, "x.json", listener)

        session.start(0)

        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        awaitTrue { synchronized(listener.errors) { listener.errors.isNotEmpty() } }
        Thread.sleep(100)

        assertTrue(listener.errors[0] is FatalApiException)
        assertEquals(1, listener.errors.size)
        assertEquals("line3", session.original(3))
        assertNull(session.translation(3))
        assertNull(cache.read("x.json"))
    }

    @Test
    fun sameLanguageNeedsNoTranslator() {
        val http = FakeClaude().apply { subtitleBody = manualJson(3) }
        val listener = Waiter()
        val session = DualSubSession(track, http, null, null, null, listener)

        session.start(0)

        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        assertEquals(0, session.findIndex(500))
        assertNull(session.translation(0))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun readableOriginalStopsBeforeTranslating() {
        val json = """{"events":[{"tStartMs":0,"dDurationMs":900,"segs":[{"utf8":"wǒ 我每天早上"}]}]}"""
        val http = FakeClaude().apply { subtitleBody = json }
        val notNeeded = java.util.concurrent.CountDownLatch(1)
        val session = DualSubSession(track, http, ClaudeTranslator(http, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), null, null,
            object : DualSubSession.Listener {
                override fun onCuesReady() {}
                override fun onTranslated() {}
                override fun onError(error: Throwable, fatal: Boolean) {}
                override fun onUnusable() = notNeeded.countDown()
            },
            isUnusable = { cues -> LangUtil.isTextReadableAs(cues.map { it.text }, "zh-Hant") })

        session.start(0)

        assertTrue(notNeeded.await(5, TimeUnit.SECONDS))
        assertTrue(http.requests.isEmpty())
        assertTrue(!session.isReady)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsed() {
        val cache = TranslationCache(tmp.root, maxBytes = 200) // fits one ~130 byte file, not two
        val cues = listOf(SubCue(0, 1, "x".repeat(100)))

        cache.write("old.json", cues, listOf("t"))
        java.io.File(tmp.root, "old.json").setLastModified(1_000)
        cache.write("new.json", cues, listOf("t"))

        assertNull(cache.read("old.json"))
        assertNotNull(cache.read("new.json"))
        assertTrue(tmp.root.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}

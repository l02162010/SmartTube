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

        session.start(125_000) // inside cue 125 -> batch 120..159 first

        assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
        awaitTrue { (0 until 200).all { session.translation(it) != null } }

        assertTrue(http.requests.any { it[0] == "line120" && it.size == DualSubSession.BATCH_SIZE })
        assertEquals("line125-tr", session.translation(125))
        assertEquals("line125", session.original(session.findIndex(125_100)))
        assertTrue(listener.errors.isEmpty())

        // Second open: straight from the cache, no requests
        awaitTrue { cache.read(name) != null }
        val http2 = FakeClaude()
        val listener2 = Waiter()
        val session2 = DualSubSession(track, http2, ClaudeTranslator(http2, "k", ClaudeTranslator.MODEL_HAIKU, "zh-Hant"), cache, name, listener2)
        session2.start(0)
        assertTrue(listener2.ready.await(5, TimeUnit.SECONDS))
        assertEquals("line7-tr", session2.translation(7))
        assertTrue(http2.requests.isEmpty())
    }

    @Test
    fun batchOrderStartsAtPlaybackPosition() {
        val batches = listOf(0, 40, 80, 120, 160).map { it to it + 40 }
        val current = 125

        val order = batches.sortedBy { (from, to) -> DualSubSession.batchPriority(from, to, current) }.map { it.first }

        assertEquals(listOf(120, 160, 80, 40, 0), order)
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

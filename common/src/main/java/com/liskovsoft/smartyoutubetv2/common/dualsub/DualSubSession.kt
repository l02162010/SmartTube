package com.liskovsoft.smartyoutubetv2.common.dualsub

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Dual subtitles of one video: fetches the original track, splits it into sentences and translates it
 * starting from the playback position. Thread safe; listener callbacks arrive on background threads.
 */
class DualSubSession(
    private val track: SubTrack,
    private val http: HttpTransport,
    /** null when no translation is needed (source == target) */
    private val translator: ClaudeTranslator?,
    private val cache: TranslationCache?,
    /** Cache file name, see [TranslationCache.fileName] */
    private val cacheName: String?,
    private val listener: Listener,
    /** Human translation track: used instead of [translator] when set */
    private val humanTranslationTrack: SubTrack? = null,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        const val BATCH_SIZE = 40
        const val WORKERS = 4

        @JvmStatic
        fun toJson3Url(url: String): String {
            val fmt = Regex("([?&]fmt=)[^&]*")
            return if (fmt.containsMatchIn(url)) {
                url.replace(fmt, "\$1json3")
            } else {
                url + (if ('?' in url) "&" else "?") + "fmt=json3"
            }
        }

        /**
         * Lower goes first: the batch at the [current] cue, then the following ones, the earlier ones last.
         */
        @JvmStatic
        fun batchPriority(from: Int, to: Int, current: Int): Long {
            return if (to > current) from.toLong() else Int.MAX_VALUE.toLong() + (current - from)
        }
    }

    interface Listener {
        /** Original cues are available (translations may still be missing). */
        fun onCuesReady()
        /** More translated lines are available. */
        fun onTranslated()
        /** [fatal] = translation stopped for this video (e.g. bad API key). */
        fun onError(error: Throwable, fatal: Boolean)
    }

    private class Batch(val from: Int, val to: Int)

    private val executor: ExecutorService = Executors.newFixedThreadPool(WORKERS + 1)
    private val cancelled = AtomicBoolean(false)
    private val pending = ArrayList<Batch>()
    private val remaining = AtomicInteger()
    private val hadErrors = AtomicBoolean(false)

    @Volatile
    private var cues: List<SubCue> = emptyList()
    @Volatile
    private var translations = AtomicReferenceArray<String?>(0)
    @Volatile
    private var positionMs = 0L

    val isReady: Boolean get() = cues.isNotEmpty()

    fun start(startPositionMs: Long) {
        positionMs = startPositionMs
        executor.execute { runSafely { load() } }
    }

    fun cancel() {
        cancelled.set(true)
        synchronized(pending) { pending.clear() }
        executor.shutdownNow()
    }

    /** Untranslated batches near this position go first (call it on seek). */
    fun setPositionMs(positionMs: Long) {
        this.positionMs = positionMs
    }

    /** -1 when nothing is shown at this position. */
    fun findIndex(positionMs: Long): Int = findCueIndex(cues, positionMs)

    fun original(index: Int): String = cues[index].text

    fun translation(index: Int): String? = translations.get(index)

    private fun load() {
        val cached = cacheName?.let { cache?.read(it) }

        if (cached != null) {
            publish(cached.cues, cached.translations)
            return
        }

        val parsed = Json3Parser.parse(fetchTrack(track), track.isAuto)

        if (cancelled.get()) {
            return
        }

        if (humanTranslationTrack != null) {
            val target = Json3Parser.parse(fetchTrack(humanTranslationTrack), false)
            if (!cancelled.get()) {
                publish(parsed, alignTranslations(parsed, target))
            }
            return
        }

        publish(parsed, null)

        if (translator == null || parsed.isEmpty()) {
            return
        }

        synchronized(pending) {
            for (from in parsed.indices step BATCH_SIZE) {
                pending.add(Batch(from, minOf(from + BATCH_SIZE, parsed.size)))
            }
            remaining.set(pending.size)
        }

        repeat(WORKERS) {
            executor.execute { runSafely { translateLoop(translator) } }
        }
    }

    private fun publish(newCues: List<SubCue>, newTranslations: List<String?>?) {
        translations = AtomicReferenceArray<String?>(newCues.size).also { array ->
            newTranslations?.forEachIndexed { i, text -> array.set(i, text) }
        }
        cues = newCues
        listener.onCuesReady()
    }

    private fun fetchTrack(track: SubTrack): String {
        val url = toJson3Url(track.url)
        var lastError: Exception? = null

        // Original tracks aren't throttled like the auto translated ones, one retry is enough
        for (attempt in 0..1) {
            if (attempt > 0) {
                sleeper(2_000)
            }
            try {
                val result = http.get(url)
                if (result.isSuccessful && result.body.isNotBlank()) {
                    return result.body
                }
                lastError = IOException("Subtitle HTTP ${result.code}")
            } catch (e: IOException) {
                lastError = e
            }
        }

        throw lastError ?: IOException("Subtitle fetch failed")
    }

    private fun translateLoop(translator: ClaudeTranslator) {
        while (!cancelled.get()) {
            val batch = takeNext() ?: return
            val lines = cues.subList(batch.from, batch.to).map { it.text }
            val context = cues.subList(maxOf(0, batch.from - ClaudeTranslator.CONTEXT_LINES), batch.from).map { it.text }

            try {
                val result = translator.translateWithSplit(lines, context)
                result.forEachIndexed { i, text -> translations.set(batch.from + i, text) }
                listener.onTranslated()
            } catch (e: FatalApiException) {
                if (cancelled.compareAndSet(false, true)) {
                    synchronized(pending) { pending.clear() }
                    listener.onError(e, true)
                }
                return
            } catch (e: TransientException) {
                // Lines of this batch stay untranslated, other batches go on
                if (hadErrors.compareAndSet(false, true)) {
                    listener.onError(e, false)
                }
            }

            if (remaining.decrementAndGet() == 0) {
                saveToCache()
            }
        }
    }

    private fun takeNext(): Batch? = synchronized(pending) {
        if (pending.isEmpty()) {
            return null
        }

        val current = findCueIndexFrom(cues, positionMs)

        val next = pending.minByOrNull { batchPriority(it.from, it.to, current) }!!
        pending.remove(next)
        next
    }

    private fun saveToCache() {
        if (hadErrors.get() || cancelled.get() || cache == null || cacheName == null) {
            return
        }

        val snapshot = translations
        cache.write(cacheName, cues, List(snapshot.length()) { snapshot.get(it) })
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: InterruptedException) {
            // cancelled
        } catch (e: Exception) {
            if (!cancelled.get()) {
                listener.onError(e, true)
            }
        }
    }
}

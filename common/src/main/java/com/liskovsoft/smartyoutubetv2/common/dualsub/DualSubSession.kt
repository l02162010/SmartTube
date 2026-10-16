package com.liskovsoft.smartyoutubetv2.common.dualsub

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Checked on the original text before translating: true = mixed/mislabeled or already readable, give up */
    private val isUnusable: (List<SubCue>) -> Boolean = { false },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {
    companion object {
        const val BATCH_SIZE = 40
        /** The batch at the playback position is small so its translation shows up within a couple of seconds */
        const val QUICK_BATCH_SIZE = 10
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
        /** The original text can't be used (mixed languages, wrong script or already readable): nothing to show. */
        fun onUnusable() {}
        /** All lines went through translation. [stats] is a human readable summary for the log. */
        fun onFinished(stats: String) {}
    }

    private class Batch(val from: Int, val to: Int)

    private val executor: ExecutorService = Executors.newFixedThreadPool(WORKERS + 1)
    private val cancelled = AtomicBoolean(false)
    /** Untranslated ranges, carved into batches by [takeNext]. Guarded by itself, like [inFlight]. */
    private val pending = ArrayList<Batch>()
    private var inFlight = 0
    private val hadErrors = AtomicBoolean(false)
    private val savedComplete = AtomicBoolean(false)
    private var translateStartMs = 0L

    @Volatile
    private var cues: List<SubCue> = emptyList()
    @Volatile
    private var translations = AtomicReferenceArray<String?>(0)
    @Volatile
    private var positionMs = 0L

    val isReady: Boolean get() = cues.isNotEmpty()

    /** How the cues were loaded (timings, sizes), for the log */
    @Volatile
    var loadInfo: String = ""
        private set

    fun start(startPositionMs: Long) {
        positionMs = startPositionMs
        executor.execute { runSafely { load() } }
    }

    fun cancel() {
        cancelled.set(true)
        synchronized(pending) { pending.clear() }
        executor.shutdownNow()
        savePartial()
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
        val startMs = System.currentTimeMillis()
        val cached = cacheName?.let { cache?.read(it) }

        if (cached != null) {
            val missing = cached.translations.count { it == null }
            loadInfo = "cache: ${cached.cues.size} cues, $missing untranslated, ${System.currentTimeMillis() - startMs}ms"
            publish(cached.cues, cached.translations)
            // Resume a translation stopped half way (the viewer left the video)
            if (translator != null && missing > 0) {
                translate(translator, untranslatedRanges(cached.translations))
            } else {
                savedComplete.set(true)
            }
            return
        }

        val body = fetchTrack(track)
        val fetchedMs = System.currentTimeMillis()
        val parsed = Json3Parser.parse(body, track.isAuto)
        loadInfo = "fetch ${fetchedMs - startMs}ms (${body.length / 1024} KB), " +
            "parse ${System.currentTimeMillis() - fetchedMs}ms, ${parsed.size} cues"

        if (cancelled.get()) {
            return
        }

        if (isUnusable(parsed)) {
            listener.onUnusable()
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

        if (translator != null && parsed.isNotEmpty()) {
            translate(translator, listOf(Batch(0, parsed.size)))
        }
    }

    private fun translate(translator: ClaudeTranslator, ranges: List<Batch>) {
        synchronized(pending) {
            pending.addAll(ranges)
        }
        translateStartMs = System.currentTimeMillis()

        repeat(WORKERS) {
            executor.execute { runSafely { translateLoop(translator) } }
        }
    }

    private fun untranslatedRanges(texts: List<String?>): List<Batch> {
        val ranges = ArrayList<Batch>()
        var start = -1
        for (i in 0..texts.size) {
            val missing = i < texts.size && texts[i] == null
            if (missing && start == -1) start = i
            if (!missing && start != -1) {
                ranges.add(Batch(start, i))
                start = -1
            }
        }
        return ranges
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
                // Lines of this batch stay untranslated (retried next time the video opens), other batches go on
                if (hadErrors.compareAndSet(false, true)) {
                    listener.onError(e, false)
                }
            }

            val finished = synchronized(pending) {
                inFlight--
                pending.isEmpty() && inFlight == 0
            }

            if (finished && !cancelled.get()) {
                save()
                savedComplete.set(true)
                val missing = (0 until translations.length()).count { translations.get(it) == null }
                listener.onFinished("${cues.size} cues in ${(System.currentTimeMillis() - translateStartMs) / 1000}s, $missing untranslated")
            }
        }
    }

    /**
     * Next batch to translate. When the playback position falls into an untranslated range, a small batch is cut
     * right there (shows up fast after opening or seeking); otherwise a full batch from the most urgent range.
     */
    private fun takeNext(): Batch? = synchronized(pending) {
        if (pending.isEmpty()) {
            return null
        }

        val current = findCueIndexFrom(cues, positionMs)
        val containing = pending.firstOrNull { current >= it.from && current < it.to }
        val range = containing ?: pending.minByOrNull { batchPriority(it.from, it.to, current) }!!
        val from = if (containing != null) current else range.from
        val to = minOf(from + if (containing != null) QUICK_BATCH_SIZE else BATCH_SIZE, range.to)

        pending.remove(range)
        if (range.from < from) pending.add(Batch(range.from, from))
        if (to < range.to) pending.add(Batch(to, range.to))
        inFlight++

        Batch(from, to)
    }

    private fun save() {
        if (cache == null || cacheName == null) {
            return
        }

        val snapshot = translations
        cache.write(cacheName, cues, List(snapshot.length()) { snapshot.get(it) })
    }

    /** Keeps what's already translated when the viewer leaves early, so it isn't paid for twice. */
    private fun savePartial() {
        if (translator == null || savedComplete.get() || cues.isEmpty()) {
            return
        }

        val snapshot = translations
        if ((0 until snapshot.length()).none { snapshot.get(it) != null }) {
            return
        }

        Thread({ runCatching { save() } }, "DualSubSave").start()
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

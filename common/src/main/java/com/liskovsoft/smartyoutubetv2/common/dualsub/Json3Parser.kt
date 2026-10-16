package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/**
 * Parses YouTube json3 captions.
 * Manual subs keep the original cue split, auto subs (asr) are rebuilt into sentences from the per-word timing.
 * Rules are ported from the Go `ytsub` reference implementation.
 */
object Json3Parser {
    const val MAX_GAP_MS = 1_500L
    /** Display width limit: CJK characters count 2, others 1 (84 Latin chars or 42 CJK chars) */
    const val MAX_WIDTH = 84
    const val MAX_SPAN_MS = 7_000L
    private const val MIN_CUE_MS = 200L
    private val SENTENCE_END = charArrayOf('.', '?', '!', '。', '？', '！', '…')
    private val TRAILING_CLOSERS = charArrayOf('"', '\'', '”', '’', ')', ']', '」', '』', '）')

    private class Word(val startMs: Long, var endMs: Long, val text: String)
    private class Seg(val text: String, val offsetMs: Long)
    private class Event(val startMs: Long, val durationMs: Long, val segs: List<Seg>)

    @JvmStatic
    fun parse(json: String, isAuto: Boolean): List<SubCue> {
        val events = readEvents(json)
        val cues = if (isAuto) buildSentences(collectWords(events)) else manualCues(events)

        return cues.sortedBy { it.startMs }
    }

    /**
     * Streams the file instead of building a JSON tree: a 2-3 hour podcast is a 2.5 MB file,
     * the tree took seconds and tens of MB on a Chromecast.
     * Keeps only events with text (skips window definitions and "aAppend" line breaks).
     */
    private fun readEvents(json: String): List<Event> {
        val events = ArrayList<Event>()
        val reader = JsonReader(StringReader(json))

        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "events" || reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                readEvent(reader)?.let { events.add(it) }
            }
            reader.endArray()
        }
        reader.endObject()

        return events
    }

    private fun readEvent(reader: JsonReader): Event? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }

        var start: Long? = null
        var duration = 0L
        var append = false
        var segs: List<Seg>? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "tStartMs" -> start = readLong(reader)
                "dDurationMs" -> duration = readLong(reader) ?: 0
                "aAppend" -> append = readLong(reader) == 1L
                "segs" -> segs = readSegs(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        return if (start == null || segs == null || append) null else Event(start, duration, segs)
    }

    private fun readSegs(reader: JsonReader): List<Seg>? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }

        val segs = ArrayList<Seg>()
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }
            var text = ""
            var offset = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "utf8" -> text = if (reader.peek() == JsonToken.STRING) reader.nextString() else { reader.skipValue(); "" }
                    "tOffsetMs" -> offset = readLong(reader) ?: 0
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            segs.add(Seg(text, offset))
        }
        reader.endArray()

        return segs
    }

    private fun readLong(reader: JsonReader): Long? {
        return if (reader.peek() == JsonToken.NUMBER) reader.nextLong() else { reader.skipValue(); null }
    }

    private fun manualCues(events: List<Event>): List<SubCue> {
        return events.mapNotNull { event ->
            val text = cleanText(event.segs.joinToString("") { it.text })
            if (text.isEmpty()) null else SubCue(event.startMs, event.startMs + event.durationMs, text)
        }
    }

    private fun collectWords(events: List<Event>): List<Word> {
        val words = ArrayList<Word>()

        for (event in events) {
            val eventEnd = event.startMs + event.durationMs
            val eventWords = ArrayList<Word>()

            for (seg in event.segs) {
                val text = seg.text.trim()
                if (text.isEmpty()) {
                    continue
                }
                eventWords.add(Word(event.startMs + seg.offsetMs, eventEnd, text))
            }

            // A word lasts until the next word of the same event starts
            for (i in 0 until eventWords.size - 1) {
                eventWords[i].endMs = eventWords[i + 1].startMs
            }

            words.addAll(eventWords)
        }

        words.sortBy { it.startMs }

        // A word never overlaps the next word
        for (i in 0 until words.size - 1) {
            if (words[i].endMs > words[i + 1].startMs) {
                words[i].endMs = words[i + 1].startMs
            }
        }

        return words
    }

    private fun buildSentences(words: List<Word>): List<SubCue> {
        val result = ArrayList<SubCue>()
        val buffer = ArrayList<Word>()

        fun emit(cueWords: List<Word>) {
            if (cueWords.isNotEmpty()) {
                val start = cueWords.first().startMs
                result.add(SubCue(start, maxOf(cueWords.last().endMs, start + MIN_CUE_MS), join(cueWords)))
            }
        }

        fun fits(cueWords: List<Word>, next: Word): Boolean {
            if (cueWords.isEmpty()) {
                return true
            }
            return width(join(cueWords + next)) <= MAX_WIDTH && next.endMs - cueWords.first().startMs <= MAX_SPAN_MS
        }

        for (word in words) {
            if (buffer.isNotEmpty() && word.startMs - buffer.last().endMs > MAX_GAP_MS) {
                emit(buffer)
                buffer.clear()
            } else if (!fits(buffer, word)) {
                // Too long: split at the longest pause in the second half rather than right here.
                // Unpunctuated ASR (Japanese) otherwise gets cut in the middle of a phrase.
                val split = pauseSplitIndex(buffer)
                emit(buffer.subList(0, split))
                val rest = ArrayList(buffer.subList(split, buffer.size))
                buffer.clear()
                if (fits(rest, word)) buffer.addAll(rest) else emit(rest)
            }

            buffer.add(word)

            if (endsSentence(word.text)) {
                emit(buffer)
                buffer.clear()
            }
        }

        emit(buffer)

        return result
    }

    /**
     * Where to split a too long cue: after the word that lasts longest (a word's end is the next word's start,
     * so a long word means the speaker paused after it), searched in the second half to keep both parts usable.
     */
    private fun pauseSplitIndex(cueWords: List<Word>): Int {
        if (cueWords.size < 4) {
            return cueWords.size
        }
        return (cueWords.size / 2 until cueWords.size).maxByOrNull { i -> cueWords[i - 1].endMs - cueWords[i - 1].startMs }!!
    }

    private fun join(cueWords: List<Word>): String {
        val text = StringBuilder()
        for (word in cueWords) {
            if (text.isNotEmpty()) {
                text.append(separator(text, word.text))
            }
            text.append(word.text)
        }
        return text.toString()
    }

    @JvmStatic
    fun endsSentence(word: String): Boolean {
        val trimmed = word.trimEnd(*TRAILING_CLOSERS)
        return trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END
    }

    @JvmStatic
    fun width(text: CharSequence): Int {
        var width = 0
        for (c in text) {
            width += if (isCjk(c) || isHangul(c)) 2 else 1
        }
        return width
    }

    private fun isHangul(c: Char) = c in '\uAC00'..'\uD7A3' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F'

    /**
     * Words are joined with a space, except next to CJK characters.
     */
    private fun separator(left: CharSequence, right: String): String {
        return if (isCjk(left.last()) || isCjk(right.first())) "" else " "
    }

    @JvmStatic
    fun isCjk(c: Char): Boolean {
        return when (Character.UnicodeBlock.of(c)) {
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS -> true
            else -> false
        }
    }

    /**
     * Collapses whitespace runs (incl. line breaks) into one space. A plain loop: Regex.replace per cue
     * was the slowest part of parsing manual subs on a Chromecast (thousands of cues in a long video).
     */
    @JvmStatic
    fun cleanText(text: String): String {
        val out = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(c)
            }
        }
        return out.toString()
    }
}

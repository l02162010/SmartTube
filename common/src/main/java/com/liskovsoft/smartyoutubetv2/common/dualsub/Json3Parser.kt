package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
    private val WHITESPACE = Regex("\\s+")

    private class Word(val startMs: Long, var endMs: Long, val text: String)

    @JvmStatic
    fun parse(json: String, isAuto: Boolean): List<SubCue> {
        val events = JsonParser().parse(json).asJsonObject.getAsJsonArray("events") ?: return emptyList()
        val objects = events.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            .filter { it.has("segs") && it.getInt("aAppend") != 1L }

        val cues = if (isAuto) buildSentences(collectWords(objects)) else manualCues(objects)

        return cues.sortedBy { it.startMs }
    }

    private fun manualCues(events: List<JsonObject>): List<SubCue> {
        return events.mapNotNull { event ->
            val text = cleanText(event.getAsJsonArray("segs").joinToString("") { it.utf8() })
            val start = event.getInt("tStartMs") ?: return@mapNotNull null
            val duration = event.getInt("dDurationMs") ?: 0

            if (text.isEmpty()) null else SubCue(start, start + duration, text)
        }
    }

    private fun collectWords(events: List<JsonObject>): List<Word> {
        val words = ArrayList<Word>()

        for (event in events) {
            val start = event.getInt("tStartMs") ?: continue
            val eventEnd = start + (event.getInt("dDurationMs") ?: 0)
            val eventWords = ArrayList<Word>()

            for (seg in event.getAsJsonArray("segs")) {
                val text = seg.utf8().trim()
                if (text.isEmpty()) {
                    continue
                }
                val offset = if (seg.isJsonObject) seg.asJsonObject.getInt("tOffsetMs") ?: 0 else 0
                eventWords.add(Word(start + offset, eventEnd, text))
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

    private fun cleanText(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun JsonElement.utf8(): String {
        if (!isJsonObject) {
            return ""
        }
        val value = asJsonObject.get("utf8")
        return if (value == null || value.isJsonNull) "" else value.asString
    }

    private fun JsonObject.getInt(name: String): Long? {
        val value = get(name)
        return if (value == null || value.isJsonNull) null else value.asLong
    }
}

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
    const val MAX_CHARS = 84
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
        val text = StringBuilder()
        var cueStart = 0L
        var cueEnd = 0L

        fun flush() {
            if (text.isNotEmpty()) {
                result.add(SubCue(cueStart, maxOf(cueEnd, cueStart + MIN_CUE_MS), text.toString()))
                text.setLength(0)
            }
        }

        for (word in words) {
            if (text.isNotEmpty()) {
                val gap = word.startMs - cueEnd
                val joined = text.length + separator(text, word.text).length + word.text.length
                if (gap > MAX_GAP_MS || joined > MAX_CHARS || word.endMs - cueStart > MAX_SPAN_MS) {
                    flush()
                }
            }

            if (text.isEmpty()) {
                cueStart = word.startMs
            } else {
                text.append(separator(text, word.text))
            }

            text.append(word.text)
            cueEnd = word.endMs

            if (endsSentence(word.text)) {
                flush()
            }
        }

        flush()

        return result
    }

    @JvmStatic
    fun endsSentence(word: String): Boolean {
        val trimmed = word.trimEnd(*TRAILING_CLOSERS)
        return trimmed.isNotEmpty() && trimmed.last() in SENTENCE_END
    }

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

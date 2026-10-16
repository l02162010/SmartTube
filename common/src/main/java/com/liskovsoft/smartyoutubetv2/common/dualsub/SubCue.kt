package com.liskovsoft.smartyoutubetv2.common.dualsub

/**
 * One subtitle sentence in the original language.
 */
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * An original subtitle track candidate (decoupled from MediaSubtitle so it can be unit tested).
 */
data class SubTrack(val languageCode: String, val url: String, val isAuto: Boolean)

/**
 * Finds the cue that is visible at [positionMs]. Returns -1 when the position falls into a gap.
 * [cues] must be sorted by start time.
 */
fun findCueIndex(cues: List<SubCue>, positionMs: Long): Int {
    var lo = 0
    var hi = cues.size - 1
    var found = -1

    // Last cue with startMs <= positionMs
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs <= positionMs) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }

    return if (found != -1 && positionMs < cues[found].endMs) found else -1
}

/**
 * Index of the first cue that ends after [positionMs] (used to decide what to translate first).
 */
fun findCueIndexFrom(cues: List<SubCue>, positionMs: Long): Int {
    var lo = 0
    var hi = cues.size

    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].endMs <= positionMs) lo = mid + 1 else hi = mid
    }

    return lo.coerceAtMost(cues.size - 1).coerceAtLeast(0)
}

/**
 * Pairs a human translation track with the original cues by time (the two tracks are often timed differently).
 * A target cue goes to every original cue it overlaps by at least half of either cue's duration.
 * Returns one text per original cue, null when nothing matches (the original is shown alone).
 */
fun alignTranslations(original: List<SubCue>, target: List<SubCue>): List<String?> {
    return original.map { cue ->
        val texts = target.filter { t ->
            val overlap = minOf(cue.endMs, t.endMs) - maxOf(cue.startMs, t.startMs)
            overlap > 0 && (overlap * 2 >= cue.endMs - cue.startMs || overlap * 2 >= t.endMs - t.startMs)
        }.map { it.text }

        if (texts.isEmpty()) null else texts.joinToString(" ")
    }
}

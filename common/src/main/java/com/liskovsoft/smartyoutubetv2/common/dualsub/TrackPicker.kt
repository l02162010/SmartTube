package com.liskovsoft.smartyoutubetv2.common.dualsub

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat

object TrackPicker {
    /** YouTube audio track id suffix of the original (not dubbed) audio, e.g. "zh-Hant.4" */
    private const val ORIGINAL_AUDIO_SUFFIX = ".4"

    /**
     * Language of the original audio track, null when the video has a single unnamed track.
     */
    @JvmStatic
    fun originalAudioLanguage(formats: List<MediaFormat>?): String? {
        return formats.orEmpty().asSequence()
            .mapNotNull { it.audioTrackId }
            .firstOrNull { it.endsWith(ORIGINAL_AUDIO_SUFFIX) }
            ?.removeSuffix(ORIGINAL_AUDIO_SUFFIX)
    }

    /**
     * Picks the subtitle track of the language actually spoken in the video, or null when that's unknown.
     * Guessing (first uploaded track, English...) put uploaded translations or multi-language
     * learning tracks on screen, so it's better to show nothing.
     *
     * 1. Machine translated tracks (tlang=) and auto-dub transcripts are never used.
     * 2. [preferredLang] (user setting) wins.
     * 3. Spoken language = [audioLang] (original audio track) or else the ASR track language
     *    (speech recognition always runs on the spoken language).
     * 4. Manual track in the spoken language, else its ASR track.
     */
    @JvmStatic
    @JvmOverloads
    fun pick(tracks: List<SubTrack>, preferredLang: String?, audioLang: String? = null): SubTrack? {
        val originals = tracks.filter { !isTranslated(it.url) && !isDubTranscript(it.url) }

        if (!preferredLang.isNullOrBlank()) {
            inLanguage(originals, preferredLang)?.let { return it }
        }

        val spoken = audioLang?.takeIf { it.isNotBlank() }
            ?: originals.firstOrNull { it.isAuto }?.languageCode
            ?: return null

        return inLanguage(originals, spoken)
    }

    private fun inLanguage(tracks: List<SubTrack>, lang: String): SubTrack? {
        val matched = tracks.filter { LangUtil.isSame(it.languageCode, lang) }
        return matched.firstOrNull { !it.isAuto } ?: matched.firstOrNull()
    }

    /**
     * A manual track in the [targetLang] (human translation), or null.
     */
    @JvmStatic
    fun pickHumanTranslation(tracks: List<SubTrack>, targetLang: String): SubTrack? {
        return tracks.firstOrNull { !it.isAuto && !isTranslated(it.url) && LangUtil.isSame(it.languageCode, targetLang) }
    }

    private fun isTranslated(url: String) = url.contains("tlang=")

    private fun isDubTranscript(url: String) = url.contains("variant=timing-optimized")
}

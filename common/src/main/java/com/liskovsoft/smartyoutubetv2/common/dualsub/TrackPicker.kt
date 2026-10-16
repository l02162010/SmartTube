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
     * Picks one original track.
     *
     * 1. Machine translated tracks (tlang=) and auto-dub transcripts are never used.
     * 2. [preferredLang] (user setting) wins, then [audioLang] (the video's original audio), manual subs first.
     * 3. Otherwise: manual track in the auto-caption language -> auto track.
     * 4. No auto track and unknown audio: the original can't be told apart from uploaded translations,
     *    so a track the viewer reads ([targetLang], dual subs then step aside) -> English -> first manual.
     */
    @JvmStatic
    @JvmOverloads
    fun pick(tracks: List<SubTrack>, preferredLang: String?, audioLang: String? = null, targetLang: String? = null): SubTrack? {
        val originals = tracks.filter { !isTranslated(it.url) && !isDubTranscript(it.url) }

        for (lang in listOf(preferredLang, audioLang)) {
            if (lang.isNullOrBlank()) {
                continue
            }
            val matched = originals.filter { LangUtil.isSame(it.languageCode, lang) }
            val track = matched.firstOrNull { !it.isAuto } ?: matched.firstOrNull()
            if (track != null) {
                return track
            }
        }

        val auto = originals.firstOrNull { it.isAuto }

        if (auto != null) {
            originals.firstOrNull { !it.isAuto && LangUtil.isSame(it.languageCode, auto.languageCode) }?.let { return it }
            return auto
        }

        return originals.firstOrNull { LangUtil.isSameLanguage(it.languageCode, targetLang) }
            ?: originals.firstOrNull { LangUtil.isSame(it.languageCode, "en") }
            ?: originals.firstOrNull()
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

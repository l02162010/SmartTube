package com.liskovsoft.smartyoutubetv2.common.dualsub

object TrackPicker {
    /**
     * Picks one original track.
     *
     * 1. Machine translated tracks (tlang=) and auto-dub transcripts are never used.
     * 2. [preferredLang] wins when set (manual subs first).
     * 3. Otherwise: manual track in the auto-caption language -> auto track -> first manual track.
     */
    @JvmStatic
    fun pick(tracks: List<SubTrack>, preferredLang: String?): SubTrack? {
        val originals = tracks.filter { !isTranslated(it.url) && !isDubTranscript(it.url) }

        if (!preferredLang.isNullOrBlank()) {
            val matched = originals.filter { LangUtil.isSame(it.languageCode, preferredLang) }
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

        return originals.firstOrNull()
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

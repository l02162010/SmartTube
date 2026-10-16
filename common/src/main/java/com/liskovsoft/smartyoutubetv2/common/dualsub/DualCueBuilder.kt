package com.liskovsoft.smartyoutubetv2.common.dualsub

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import com.google.android.exoplayer2.text.Cue

object DualCueBuilder {
    /** The original line is one size smaller than the translation */
    private const val ORIGINAL_SCALE = 0.8f

    /**
     * One cue, translation on top and original below, so the user's subtitle position setting still applies.
     * Needs embedded styles enabled on the SubtitleView, otherwise the size span is dropped.
     */
    @JvmStatic
    fun build(translation: CharSequence?, original: CharSequence?): List<Cue> {
        val text = SpannableStringBuilder()

        if (!translation.isNullOrEmpty()) {
            text.append(translation)
        }

        if (!original.isNullOrEmpty()) {
            if (text.isNotEmpty()) {
                text.append('\n')
                val start = text.length
                text.append(original)
                text.setSpan(RelativeSizeSpan(ORIGINAL_SCALE), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                text.append(original)
            }
        }

        return if (text.isEmpty()) emptyList() else listOf(Cue(text))
    }
}

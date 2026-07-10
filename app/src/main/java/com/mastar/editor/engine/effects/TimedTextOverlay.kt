package com.mastar.editor.engine.effects

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextOverlay

/**
 * A text overlay that is only visible inside its clip's timeline window.
 * Applied at composition level during export, so presentation time here is
 * absolute project-timeline time — exactly what ClipEntity stores.
 */
@UnstableApi
class TimedTextOverlay(
    text: String,
    private val startMs: Long,
    private val endMs: Long,
) : TextOverlay() {

    private val visibleText = SpannableString(text).apply {
        setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(72), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // A single space renders as an invisible 1-glyph bitmap; TextOverlay does
    // not support fully empty text, so this is the "hidden" state.
    private val hiddenText = SpannableString(" ")

    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1000
        return if (timeMs in startMs until endMs) visibleText else hiddenText
    }
}

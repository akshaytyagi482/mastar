package com.mastar.editor.engine.effects

import org.json.JSONObject

/**
 * Styled text stored in a TEXT clip's payload as JSON. Older clips that
 * stored plain strings parse as default-styled text.
 */
data class TextPayload(
    val text: String,
    /** ARGB color. */
    val color: Long = 0xFFFFFFFF,
    val sizeSp: Int = 28,
    val bold: Boolean = false,
    /** Dark pill behind the text, like CapCut's background style. */
    val background: Boolean = false,
    /** "sans" | "serif" | "mono" | "cursive" */
    val font: String = "sans",
) {
    fun toJson(): String = JSONObject().apply {
        put("text", text)
        put("color", color)
        put("sizeSp", sizeSp)
        put("bold", bold)
        put("background", background)
        put("font", font)
    }.toString()

    companion object {
        val FONTS = listOf("sans", "serif", "mono", "cursive")

        val COLORS = listOf(
            0xFFFFFFFF, 0xFF000000, 0xFFFF9933, 0xFFFF1744,
            0xFF00E676, 0xFF2979FF, 0xFFFFD600, 0xFFE040FB,
        )

        fun fromPayload(payload: String?): TextPayload {
            if (payload.isNullOrEmpty()) return TextPayload("")
            return try {
                val json = JSONObject(payload)
                TextPayload(
                    text = json.optString("text"),
                    color = json.optLong("color", 0xFFFFFFFF),
                    sizeSp = json.optInt("sizeSp", 28),
                    bold = json.optBoolean("bold", false),
                    background = json.optBoolean("background", false),
                    font = json.optString("font", "sans"),
                )
            } catch (e: Exception) {
                // Legacy plain-string payloads.
                TextPayload(payload)
            }
        }
    }
}

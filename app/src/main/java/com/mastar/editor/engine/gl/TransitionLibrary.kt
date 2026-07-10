package com.mastar.editor.engine.gl

import android.content.Context

/**
 * Zero-network asset principle: every transition ships inside the APK as a
 * .glsl file under assets/transitions (MIT snippets from gl-transitions.com).
 * Shaders are tiny text files, so caching them in memory is free and makes
 * the very first tap on a transition instant.
 */
class TransitionLibrary(private val context: Context) {

    data class TransitionInfo(
        val id: String,
        /** Hinglish-friendly search tags: "fade", "dhoom", "swipe", "gol"... */
        val tags: List<String>,
    )

    private val cache = mutableMapOf<String, String>()

    fun listTransitions(): List<TransitionInfo> = BUILT_IN

    /** Search mapped to Indian internet culture — matches id or any tag. */
    fun search(query: String): List<TransitionInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return BUILT_IN
        return BUILT_IN.filter { info ->
            info.id.contains(q) || info.tags.any { it.contains(q) }
        }
    }

    fun loadGlsl(id: String): String = cache.getOrPut(id) {
        context.assets.open("transitions/$id.glsl").bufferedReader().use { it.readText() }
    }

    companion object {
        private val BUILT_IN = listOf(
            TransitionInfo("fade", listOf("fade", "smooth", "soft", "dheere")),
            TransitionInfo("directionalwipe", listOf("wipe", "swipe", "slide", "side")),
            TransitionInfo("circleopen", listOf("circle", "gol", "open", "reveal")),
            TransitionInfo("wiperight", listOf("wipe", "right", "slide")),
        )
    }
}

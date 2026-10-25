package com.mastar.editor.engine.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Chooses the largest requested-or-lower AVC frame size reported by a device encoder. */
internal object AvcResolutionSelector {
    fun select(canvasWidth: Int, canvasHeight: Int, requestedShortSide: Int): Pair<Int, Int> {
        require(canvasWidth > 0 && canvasHeight > 0)

        val shortSide = minOf(canvasWidth, canvasHeight)
        val scale = requestedShortSide / shortSide.toFloat()
        val requested = evenSize(canvasWidth * scale, canvasHeight * scale)
        val candidates = buildList {
            add(requested)
            listOf(1440, 1080, 720, 480)
                .filter { it < requestedShortSide }
                .forEach { target ->
                    val fallbackScale = target / shortSide.toFloat()
                    add(evenSize(canvasWidth * fallbackScale, canvasHeight * fallbackScale))
                }
        }.distinct()

        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } }
        if (encoders.isEmpty()) return requested

        // Prefer hardware encoders where the platform exposes that signal; on
        // older Android releases use the complete AVC encoder list.
        val hardwareEncoders = if (Build.VER×m÷¶‰Ëkºwµç\Ù][˜ÛÙ\‘˜XİÜJ[˜ÛÙ\‘˜XİÜJCBˆ˜Y\İ[™\ŠØš™Xİˆ˜[œÙ›Ü›Y\‹“\İ[™\ˆÃBˆİ™\œšYH[ˆÛÛÛ\]Y
ÛÛ\ÜÚ][ÛˆÛÛ\ÜÚ][Û‹^Ü™\İ[ˆ^Ü™\İ[
HÃBˆ\İ[™\‹›Û”İ]Jİ]K‘Û™Jİ]]š[JJCBˆCBƒBˆİ™\œšYH[ˆÛ‘\œ›ÜŠBˆÛÛ\ÜÚ][ÛˆÛÛ\ÜÚ][Û‹Bˆ^Ü™\İ[ˆ^Ü™\İ[Bˆ^Ü^Ù\[Ûˆ^Ü^Ù\[Û‹Bˆ
HÃBˆ\İ[™\‹›Û”İ]Jİ]K‘˜Z[Y
^Ü^Ù\[ÛŠJCBˆCBˆJCBˆ˜Z[

CBƒBˆ˜[œÙ›Ü›Y\ˆHBˆ\İ[™\‹›Û”İ]Jİ]K‘^Ü[™Ê
JCBˆœİ\
ÛÛ\ÜÚ][Û‹İ]]š[K˜XœÛÛ]T]
CBˆCBƒBˆÊŠˆÛÈ^Ü›ÙÜ™\ÜÎÈØ[œ›ÛHHÛÜ›İ][™HXÚÙ\ˆÚ[H^Ü[™Ëˆ
‹ÃBˆ[ˆ]Y\T›ÙÜ™\ÜÊ
Nˆ[ÃBˆ˜[Û\ˆH›ÙÜ™\ÜÒÛ\Š
CBˆ˜[œÙ›Ü›Y\Ë™Ù]›ÙÜ™\ÜÊÛ\ŠCBˆ™]\›ˆÛ\‹œ›ÙÜ™\ÜÃBˆCBƒBˆ[ˆØ[˜Ù[

HÃBˆ˜[œÙ›Ü›Y\Ë˜Ø[˜Ù[

CBˆ˜[œÙ›Ü›Y\ˆH[BˆCBŸCB
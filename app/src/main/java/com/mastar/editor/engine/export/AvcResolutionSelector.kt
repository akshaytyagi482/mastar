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
        val hardwareEncoders = if (Build.VERSION.SDK_INT >= 29) {
            encoders.filter { it.isHardwareAccelerated }
        } else {
            encoders.filterNot { it.name.startsWith("OMX.google.", true) || it.name.startsWith("c2.android.", true) }
        }
        val softwareOrUnknownEncoders = encoders - hardwareEncoders.toSet()

        fun supportedSize(codecInfos: List<MediaCodecInfo>): Pair<Int, Int>? =
            candidates.firstOrNull { (width, height) ->
                codecInfos.any { info ->
                    runCatching {
                        info.getCapabilitiesForType("video/avc")
                            .videoCapabilities
                            .isSizeSupported(width, height)
                    }.getOrDefault(false)
                }
            }

        return supportedSize(hardwareEncoders)
            ?: supportedSize(softwareOrUnknownEncoders)
            ?: requested
    }
    private fun evenSize(width: Float, height: Float): Pair<Int, Int> =
        (width.toInt() and -2) to (height.toInt() and -2)
}


package com.mastar.editor.engine.gl

/**
 * JNI surface into libmastar_engine.so (see app/src/main/cpp/).
 * Transition handles are opaque native pointers; every call that touches
 * a handle must run on the thread that owns the EGL context.
 */
object NativeBridge {

    init {
        System.loadLibrary("mastar_engine")
    }

    /** Compiles a gl-transitions GLSL snippet. Returns 0 on compile failure. */
    external fun nativeCreateTransition(transitionGlsl: String): Long

    external fun nativeDrawTransition(
        handle: Long,
        progress: Float,
        fromTexture: Int,
        toTexture: Int,
        width: Int,
        height: Int,
    )

    external fun nativeDestroyTransition(handle: Long)

    external fun nativeStartAudioEngine(): Boolean

    external fun nativeStopAudioEngine()
}

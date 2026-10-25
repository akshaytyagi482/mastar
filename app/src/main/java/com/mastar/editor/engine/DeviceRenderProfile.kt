package com.mastar.editor.engine

import android.app.ActivityManager
import android.content.Context

/**
 * Small, conservative render targets for phones with less memory bandwidth.
 * Preview size is independent from export size: editing stays responsive
 * without reducing the quality of the final file.
 */
data class DeviceRenderProfile(
    val previewShortSidePixels: Int,
    val transitionLongSidePixels: Int,
    val isMemoryConstrained: Boolean,
) {
    companion object {
        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB

        fun from(context: Context): DeviceRenderProfile {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val totalMemory = memoryInfo.totalMem
            val memoryClassBytes = manager.memoryClass.toLong() * MIB

            val constrained = manager.isLowRamDevice ||
                totalMemory <= 2L * GIB ||
                memoryClassBytes <= 128L * MIB
            val modest = constrained ||
                totalMemory <= 4L * GIB ||
                memoryClassBytes <= 192L * MIB

            return DeviceRenderProfile(
                previewShortSidePixels = when {
                    constrained -> 360
                    modest -> 480
                    else -> 540
                },
                transitionLongSidePixels = if (constrained) 480 else 720,
                isMemoryConstrained = constrained,
            )
        }
    }
}

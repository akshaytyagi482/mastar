package com.mastar.editor.engine.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies a finished export into the device gallery (Movies/Mastar) so it
 * shows up in the gallery app and share sheets immediately.
 */
object GalleryPublisher {

    /** Returns a user-readable location, or null if publishing failed. */
    fun publish(context: Context, file: File): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) publishModern(context, file)
        else publishLegacy(context, file)
    }.getOrNull()

    private fun publishModern(context: Context, file: File): String {
        val name = "Mastar_${file.nameWithoutExtension}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Mastar")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("openOutputStream failed")
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Movies/Mastar/$name"
    }

    private fun publishLegacy(context: Context, file: File): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Mastar",
        ).apply { mkdirs() }
        val target = File(dir, "Mastar_${System.currentTimeMillis()}.mp4")
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null
        )
        return "Movies/Mastar/${target.name}"
    }
}

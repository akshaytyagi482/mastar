package com.mastar.editor.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Extracts amplitude peaks from an audio source for waveform rendering —
 * creators sync cuts to the beats they can SEE. Decodes once per source on
 * IO, caches in memory and on disk (cache/waveforms).
 */
object Waveform {

    /** Peak amplitude (0..1) per bucket of [BUCKET_MS] source milliseconds. */
    const val BUCKET_MS = 100L

    private const val MAX_DECODE_MS = 10 * 60 * 1000L // waveform cap: 10 min
    private val memoryCache = LruCache<String, FloatArray>(24)
    private val inFlight = Mutex()

    suspend fun peaks(context: Context, sourceUri: String): FloatArray? =
        withContext(Dispatchers.IO) {
            memoryCache.get(sourceUri)?.let { return@withContext it }
            // One decode at a time: budget phones stall with parallel codecs.
            inFlight.withLock {
                memoryCache.get(sourceUri)?.let { return@withLock it }
                val cacheFile = cacheFileFor(context, sourceUri)
                val fromDisk = runCatching { readCache(cacheFile) }.getOrNull()
                if (fromDisk != null) {
                    memoryCache.put(sourceUri, fromDisk)
                    return@withLock fromDisk
                }
                val decoded = runCatching { decodePeaks(context, sourceUri) }.getOrNull()
                if (decoded != null) {
                    memoryCache.put(sourceUri, decoded)
                    runCatching { writeCache(cacheFile, decoded) }
                }
                decoded
            }
        }

    private fun cacheFileFor(context: Context, sourceUri: String): File {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(sourceUri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "waveforms").apply { mkdirs() }
        return File(dir, "$digest.wf")
    }

    private fun readCache(file: File): FloatArray? {
        if (!file.exists()) return null
        DataInputStream(file.inputStream().buffered()).use { input ->
            val size = input.readInt()
            if (size <= 0 || size > 1_000_000) return null
            return FloatArray(size) { input.readFloat() }
        }
    }

    private fun writeCache(file: File, peaks: FloatArray) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(peaks.size)
            peaks.forEach { out.writeFloat(it) }
        }
    }

    private fun decodePeaks(context: Context, sourceUri: String): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(sourceUri), null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val durationMs = (format.getLong(MediaFormat.KEY_DURATION) / 1000)
                .coerceAtMost(MAX_DECODE_MS)
            val bucketCount = (durationMs / BUCKET_MS).toInt().coerceAtLeast(1)
            val peaks = FloatArray(bucketCount)

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format) ?: return null
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime / 1000 > MAX_DECODE_MS) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            val pcm = outBuffer.duplicate()
                            pcm.position(bufferInfo.offset)
                            pcm.limit(bufferInfo.offset + bufferInfo.size)
                            var sampleIndex =
                                bufferInfo.presentationTimeUs * sampleRate / 1_000_000 * channels
                            while (pcm.remaining() >= 2) {
                                val v = abs(pcm.short.toInt()) / 32768f
                                val frame = sampleIndex / channels
                                val bucket =
                                    (frame * 1000 / sampleRate / BUCKET_MS).toInt()
                                if (bucket in 0 until bucketCount && v > peaks[bucket]) {
                                    peaks[bucket] = v
                                }
                                sampleIndex++
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            return peaks
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}

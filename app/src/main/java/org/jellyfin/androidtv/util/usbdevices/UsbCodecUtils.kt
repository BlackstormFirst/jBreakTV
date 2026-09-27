package org.jellyfin.androidtv.util.usbdevices

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import java.io.File
import java.util.Locale
import kotlin.math.abs

@OptIn(UnstableApi::class)
object MediaCodecHelper {
    fun normalizeAudioCodec(mimeLower: String): String = when {
        mimeLower.contains("truehd") || mimeLower.contains("mlp") -> "truehd"
        mimeLower.contains("eac3") || mimeLower.contains("e-ac3") -> "eac3"
        mimeLower.contains("ac3") || mimeLower.contains("ac-3") -> "ac3"
        mimeLower.contains("dts-hd") || mimeLower.contains("dtshd") -> "dts_hd"
        mimeLower.contains("dts") -> "dts"
        mimeLower.contains("flac") -> "flac"
        mimeLower.contains("opus") -> "opus"
        mimeLower.contains("vorbis") -> "vorbis"
        mimeLower.contains("alac") -> "alac"
        mimeLower.contains("mp4a") || mimeLower.contains("aac") -> "aac"
        mimeLower.contains("mp3") || mimeLower.contains("mpeg-l3") -> "mp3"
        mimeLower.contains("mp2") || mimeLower.contains("mpeg-l2") -> "mp2"
        mimeLower.contains("pcm") || mimeLower.contains("raw") || mimeLower.contains("wav") -> "pcm"
        mimeLower.contains("wma") -> "wma"
        mimeLower.contains("ape") -> "ape"
        else -> mimeLower.substringAfter('/')
    }

    fun normalizeSubtitleCodec(mimeLower: String): String = when {
        mimeLower.contains("ssa") || mimeLower.contains("ass") -> "ass"
        mimeLower.contains("subrip") || mimeLower.contains("srt") || mimeLower.contains("text") -> "subrip"
        mimeLower.contains("vobsub") || mimeLower.contains("idx") -> "vobsub"
        mimeLower.contains("pgs") || mimeLower.contains("presentation-graphic") -> "pgs"
        else -> mimeLower.substringAfter('/')
    }

    fun isSubtitleMime(mimeLower: String): Boolean =
        mimeLower.startsWith("text/") ||
                mimeLower.startsWith("application/") ||
                mimeLower.contains("subtitle") ||
                mimeLower.contains("ass") ||
                mimeLower.contains("ssa") ||
                mimeLower.contains("subrip") ||
                mimeLower.contains("vobsub") ||
                mimeLower.contains("pgs") ||
                mimeLower.contains("tx3g") ||
                mimeLower.contains("ttml") ||
                mimeLower.contains("webvtt")

    fun isTextSubtitle(codec: String, mimeLower: String): Boolean {
        val norm = codec.lowercase(Locale.ROOT)
        return norm == "subrip" || norm == "srt" || norm == "ass" || norm == "ssa" ||
                norm == "webvtt" || norm == "ttml" || norm == "tx3g" || norm == "text" ||
                mimeLower.startsWith("text/")
    }
}

@OptIn(UnstableApi::class)
object AudioChannelHelper {
    fun formatChannelLayout(channels: Int): String = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        3 -> "2.1"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        10 -> "7.1.2"
        12 -> "7.1.4"
        14 -> "9.1.4"
        16 -> "9.1.6"
        else -> if (channels > 0) "$channels ch" else ""
    }

    fun detectAudioProfile(format: Format): String? {
        val codecsLower = format.codecs?.lowercase(Locale.ROOT) ?: ""
        val mimeLower = format.sampleMimeType?.lowercase(Locale.ROOT) ?: ""

        return when {
            mimeLower.contains("eac3-joc") || codecsLower.contains("ec+3") || codecsLower.contains("joc") -> "Atmos"
            codecsLower.contains("dtshd") || mimeLower.contains("dts-hd") -> "DTS-HD"
            format.codecs?.isNotBlank() == true -> format.codecs
            else -> null
        }
    }
}

@OptIn(UnstableApi::class)
object VideoHelper {
    fun detectVideoRange(format: Format): Pair<VideoRangeType, VideoRange> {
        val mimeLower = format.sampleMimeType?.lowercase(Locale.ROOT) ?: ""
        val codecsLower = format.codecs?.lowercase(Locale.ROOT) ?: ""
        val isDovi = mimeLower.contains("dolby-vision") || mimeLower.contains("dovi") ||
                codecsLower.startsWith("dvh1") || codecsLower.startsWith("dvhe") ||
                codecsLower.startsWith("dva1") || codecsLower.startsWith("dvav")

        val colorInfo = format.colorInfo
        val transfer = colorInfo?.colorTransfer

        return when {
            isDovi -> {
                if (transfer == C.COLOR_TRANSFER_ST2084) {
                    Pair(VideoRangeType.DOVI_WITH_HDR10, VideoRange.HDR)
                } else {
                    Pair(VideoRangeType.DOVI, VideoRange.HDR)
                }
            }
            transfer == C.COLOR_TRANSFER_ST2084 -> Pair(VideoRangeType.HDR10, VideoRange.HDR)
            transfer == C.COLOR_TRANSFER_HLG -> Pair(VideoRangeType.HLG, VideoRange.HDR)
            else -> Pair(VideoRangeType.SDR, VideoRange.SDR)
        }
    }

    fun calculateAspectRatio(width: Int, height: Int, format: Format): String? {
        if (width <= 0 || height <= 0) return null
        val sar = if (format.pixelWidthHeightRatio > 0f) format.pixelWidthHeightRatio else 1.0f
        val darRatio = (width * sar) / height.toFloat()

        return when {
            abs(darRatio - 1.777f) < 0.08f -> "16:9"
            abs(darRatio - 1.333f) < 0.08f -> "4:3"
            abs(darRatio - 2.35f) < 0.1f -> "2.35:1"
            abs(darRatio - 2.40f) < 0.1f -> "2.40:1"
            abs(darRatio - 1.85f) < 0.08f -> "1.85:1"
            else -> String.format(Locale.US, "%.2f:1", darRatio)
        }
    }

    data class ProbedVideoMetadata(
        val isInterlaced: Boolean = false,
        val frameRate: Float? = null,
    )

    fun snapToStandardFrameRate(fps: Float): Float {
        val standardRates = floatArrayOf(23.976f, 24.0f, 25.0f, 29.97f, 30.0f, 48.0f, 50.0f, 59.94f, 60.0f, 120.0f)
        for (stdRate in standardRates) {
            if (abs(fps - stdRate) / stdRate < 0.03f) {
                return stdRate
            }
        }
        return fps
    }

    fun probeVideoMetadata(file: File): ProbedVideoMetadata {
        var isInterlaced = false
        val ext = file.extension.lowercase(Locale.ROOT)
        var frameRate: Float? = when (ext) {
            "mkv", "webm", "mka" -> UsbContainerParser.extractMatroskaFrameRate(file)
            "mp4", "m4v", "mov" -> UsbContainerParser.extractMp4FrameRate(file)
            else -> null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var videoTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    if (format.containsKey("scan-type")) {
                        val scanType = format.getString("scan-type")
                        if (scanType?.lowercase(Locale.ROOT) == "interlaced") isInterlaced = true
                    }
                    if (format.containsKey("interlaced")) {
                        if (format.getInteger("interlaced") == 1) isInterlaced = true
                    }
                    if (frameRate == null && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val rate = try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (_: Exception) {
                            try {
                                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                            } catch (_: Exception) {
                                format.getString(MediaFormat.KEY_FRAME_RATE)?.toFloatOrNull()
                            }
                        }
                        if (rate != null && rate > 0f) frameRate = snapToStandardFrameRate(rate)
                    }
                    break
                }
            }

            if (frameRate == null && videoTrackIndex >= 0) {
                extractor.selectTrack(videoTrackIndex)
                val timestamps = mutableListOf<Long>()
                var sampleCount = 0
                while (sampleCount < 15 && extractor.sampleTime >= 0L) {
                    timestamps.add(extractor.sampleTime)
                    sampleCount++
                    if (!extractor.advance()) break
                }
                if (timestamps.size >= 3) {
                    val sorted = timestamps.sorted()
                    val spanUs = sorted.last() - sorted.first()
                    if (spanUs > 0) {
                        val rawFps = ((timestamps.size - 1) * 1_000_000.0 / spanUs).toFloat()
                        val snappedFps = snapToStandardFrameRate(rawFps)
                        if (snappedFps in 10.0f..120.0f) {
                            frameRate = snappedFps
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        if (frameRate == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val countStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val count = countStr?.toFloatOrNull()
                    val durationMs = durationStr?.toFloatOrNull()
                    if (count != null && durationMs != null && count > 0f && durationMs > 0f) {
                        val fps = (count * 1000f) / durationMs
                        if (fps in 10.0f..120.0f) {
                            frameRate = snapToStandardFrameRate(fps)
                        }
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }

        return ProbedVideoMetadata(isInterlaced, frameRate)
    }

    fun extractFallbackDimensions(file: File): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val w = wStr?.toIntOrNull()
            val h = hStr?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                Pair(w, h)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }
}

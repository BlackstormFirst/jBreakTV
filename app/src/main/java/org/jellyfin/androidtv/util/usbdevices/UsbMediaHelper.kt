package org.jellyfin.androidtv.util.usbdevices

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
object UsbMediaHelper {

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mts", "avi", "flv", "3gp", "3g2", "vob", "mpg", "mpeg", "ogv", "mov"
    )

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "aac", "flac", "m4a", "ogg", "oga", "opus", "wav", "wma", "amr", "aiff", "aif", "mid", "midi"
    )

    val SUBTITLE_EXTENSIONS = setOf(
        "srt", "vtt", "ssa", "ass"
    )

    val PLAYLIST_EXTENSIONS = setOf(
        "m3u", "m3u8", "pls"
    )

    fun isMediaFile(file: File): Boolean {
        if (!file.isFile) return false
        return isVideoFile(file) || isAudioFile(file)
    }

    fun isVideoFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return VIDEO_EXTENSIONS.contains(ext)
    }

    fun isAudioFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return AUDIO_EXTENSIONS.contains(ext)
    }

    fun isSubtitleFile(file: File): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        return SUBTITLE_EXTENSIONS.contains(ext)
    }

    fun getMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "ts", "m2ts", "mts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            "flv" -> MimeTypes.VIDEO_FLV
            "3gp", "3g2" -> MimeTypes.VIDEO_H263
            "vob", "mpg", "mpeg" -> MimeTypes.VIDEO_MPEG
            "ogv" -> MimeTypes.VIDEO_OGG
            "mov" -> "video/quicktime"
            "mp3" -> MimeTypes.AUDIO_MPEG
            "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "m4a" -> MimeTypes.AUDIO_MP4
            "ogg", "oga", "opus" -> MimeTypes.AUDIO_OGG
            "wav" -> MimeTypes.AUDIO_RAW
            "wma" -> "audio/x-ms-wma"
            "amr" -> MimeTypes.AUDIO_AMR_NB
            "aiff", "aif" -> MimeTypes.AUDIO_ALAW
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ssa", "ass" -> MimeTypes.TEXT_SSA
            else -> if (isVideoFile(file)) "video/*" else if (isAudioFile(file)) "audio/*" else "*/*"
        }
    }

    /**
     * Natural sort comparator for Files based on their names.
     */
    val naturalFileComparator = Comparator<File> { f1, f2 ->
        naturalCompare(f1.name, f2.name)
    }

    fun naturalCompare(s1: String, s2: String): Int {
        val p1 = splitDigits(s1)
        val p2 = splitDigits(s2)
        val minSize = minOf(p1.size, p2.size)
        for (i in 0 until minSize) {
            val token1 = p1[i]
            val token2 = p2[i]
            val num1 = token1.toLongOrNull()
            val num2 = token2.toLongOrNull()

            if (num1 != null && num2 != null) {
                val cmp = num1.compareTo(num2)
                if (cmp != 0) return cmp
            } else {
                val cmp = token1.compareTo(token2, ignoreCase = true)
                if (cmp != 0) return cmp
            }
        }
        return p1.size.compareTo(p2.size)
    }

    private fun splitDigits(s: String): List<String> {
        val list = mutableListOf<String>()
        val sb = StringBuilder()
        var isDigit = false

        for (ch in s) {
            val currIsDigit = ch.isDigit()
            if (sb.isNotEmpty() && currIsDigit != isDigit) {
                list.add(sb.toString())
                sb.clear()
            }
            sb.append(ch)
            isDigit = currIsDigit
        }
        if (sb.isNotEmpty()) {
            list.add(sb.toString())
        }
        return list
    }

    /**
     * Find sidecar external subtitle files in the same directory matching the base name of the media file.
     * e.g. "movie.mkv" matches "movie.srt", "movie.fr.vtt", etc.
     */
    fun findSidecarSubtitles(mediaFile: File): List<File> {
        val parent = mediaFile.parentFile ?: return emptyList()
        val mediaNameWithoutExt = mediaFile.nameWithoutExtension.lowercase(Locale.ROOT)

        val files = parent.listFiles() ?: return emptyList()
        return files.filter { file ->
            isSubtitleFile(file) && file.nameWithoutExtension.lowercase(Locale.ROOT).startsWith(mediaNameWithoutExt)
        }.sortedWith(naturalFileComparator)
    }
}

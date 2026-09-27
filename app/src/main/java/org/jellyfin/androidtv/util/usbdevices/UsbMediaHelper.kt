package org.jellyfin.androidtv.util.usbdevices

import java.io.File
import java.util.Locale

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
    fun findSidecarSubtitles(mediaFile: File): List<File> = UsbSubtitleUtils.findSidecarSubtitles(mediaFile)
}

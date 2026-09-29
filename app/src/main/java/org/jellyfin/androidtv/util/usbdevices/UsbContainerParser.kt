package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.ChapterInfo
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.util.Locale

object UsbContainerParser {

    private val thumbnailMutex = Mutex()

    private data class RawChapter(
        val startTicks: Long,
        val title: String
    )

    fun extractChapters(file: File, context: Context? = null): List<ChapterInfo> {
        if (!file.exists() || file.length() <= 0L) return emptyList()
        val ext = file.extension.lowercase(Locale.ROOT)
        val rawChapters = when (ext) {
            "mkv", "webm", "mka" -> extractMatroskaChapters(file)
            "mp4", "m4v", "mov" -> extractMp4Chapters(file)
            else -> emptyList()
        }

        if (rawChapters.isEmpty()) return emptyList()

        val now = LocalDateTime.now()
        val chapters = rawChapters.mapIndexed { index, chapter ->
            val cacheFile = context?.cacheDir?.let { cacheDir ->
                File(cacheDir, "chapters/${file.absolutePath.hashCode()}_chap_${index}.jpg")
            }
            val imageTag = cacheFile?.absolutePath?.takeIf { cacheFile.exists() }
                ?: cacheFile?.absolutePath.orEmpty()

            ChapterInfo(
                startPositionTicks = chapter.startTicks,
                name = chapter.title.ifBlank { "Chapitre ${index + 1}" },
                imagePath = null,
                imageDateModified = now,
                imageTag = imageTag.ifBlank { null },
            )
        }

        if (context != null) {
            generateThumbnailsAsync(file, rawChapters, context)
        }

        return chapters
    }

    private fun generateThumbnailsAsync(
        file: File,
        rawChapters: List<RawChapter>,
        context: Context
    ) {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            thumbnailMutex.withLock {
                val cacheDir = File(context.cacheDir, "chapters")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                var retriever: MediaMetadataRetriever? = null
                try {
                    for (index in rawChapters.indices) {
                        coroutineContext.ensureActive()
                        if (!file.exists() || !file.canRead()) break

                        val chapter = rawChapters[index]
                        val cacheFile = File(cacheDir, "${file.absolutePath.hashCode()}_chap_${index}.jpg")
                        if (cacheFile.exists() && cacheFile.length() > 0L) continue

                        if (retriever == null) {
                            retriever = MediaMetadataRetriever().apply {
                                setDataSource(file.absolutePath)
                            }
                        }

                        try {
                            val timeUs = chapter.startTicks / 10L
                            val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (bitmap != null) {
                                val scaled = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                                FileOutputStream(cacheFile).use { out ->
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 94, out)
                                }
                                bitmap.recycle()
                                if (scaled != bitmap) scaled.recycle()
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Timber.w(e, "UsbContainerParser: Error generating chapter thumbnail $index for ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Timber.w(e, "UsbContainerParser: Error in thumbnail generation for ${file.name}")
                    }
                } finally {
                    runCatching { retriever?.release() }
                }
            }
        }
    }

    fun extractMatroskaFrameRate(file: File): Float? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                var pos = 0L
                val maxScan = minOf(fileLength, 8 * 1024 * 1024L)

                while (pos < maxScan) {
                    raf.seek(pos)
                    val id = readEbmlId(raf) ?: break
                    val size = readEbmlVint(raf) ?: break
                    val dataPos = raf.filePointer

                    if (id == 0x18538067L) { // Segment
                        pos = dataPos
                        continue
                    }

                    if (id == 0x1654AE6BL) { // Tracks
                        return parseMatroskaTracksForFrameRate(raf, dataPos, size)
                    } else if (id == 0x1F43B675L) { // Cluster
                        break
                    }

                    pos = dataPos + size
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "UsbContainerParser: Error extracting Matroska FrameRate")
        }
        return null
    }

    private fun parseMatroskaTracksForFrameRate(
        raf: RandomAccessFile,
        dataPos: Long,
        size: Long
    ): Float? {
        val end = minOf(dataPos + size, raf.length())
        var pos = dataPos

        while (pos < end) {
            raf.seek(pos)
            val id = readEbmlId(raf) ?: break
            val elemSize = readEbmlVint(raf) ?: break
            val elemData = raf.filePointer

            if (id == 0xAEL) { // TrackEntry
                var isVideoTrack = false
                var defaultDurationNs = 0L
                var headerFrameRate = 0f

                var childPos = elemData
                val childEnd = elemData + elemSize

                while (childPos < childEnd) {
                    raf.seek(childPos)
                    val childId = readEbmlId(raf) ?: break
                    val childSize = readEbmlVint(raf) ?: break
                    val childData = raf.filePointer

                    when (childId) {
                        0x83L -> { // TrackType
                            val trackType = readEbmlUint(raf, childSize)
                            if (trackType == 1L) isVideoTrack = true
                        }
                        0x23E383L -> { // DefaultDuration (nanoseconds per frame)
                            defaultDurationNs = readEbmlUint(raf, childSize)
                        }
                        0x2383E3L -> { // FrameRate (Float)
                            if (childSize == 4L) {
                                headerFrameRate = java.lang.Float.intBitsToFloat(readUint32(raf).toInt())
                            } else if (childSize == 8L) {
                                headerFrameRate = Double.fromBits(readUint64(raf)).toFloat()
                            }
                        }
                    }
                    childPos = childData + childSize
                }

                if (isVideoTrack) {
                    if (headerFrameRate > 0f) {
                        return VideoHelper.snapToStandardFrameRate(headerFrameRate)
                    }
                    if (defaultDurationNs > 0L) {
                        val fps = (1_000_000_000.0 / defaultDurationNs).toFloat()
                        return VideoHelper.snapToStandardFrameRate(fps)
                    }
                }
            }
            pos = elemData + elemSize
        }
        return null
    }

    fun extractMp4FrameRate(file: File): Float? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = 0L

                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val size = readUint32(raf)
                    val type = readFourCC(raf)
                    val boxSize = if (size == 1L) readUint64(raf) else if (size == 0L) fileLen - pos else size

                    if (type == "moov") {
                        return parseMp4MoovForFrameRate(raf, pos + 8, boxSize - 8)
                    }
                    if (boxSize < 8) break
                    pos += boxSize
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "UsbContainerParser: Error extracting MP4 FrameRate")
        }
        return null
    }

    private fun parseMp4MoovForFrameRate(
        raf: RandomAccessFile,
        dataPos: Long,
        dataSize: Long
    ): Float? {
        val end = minOf(dataPos + dataSize, raf.length())
        var pos = dataPos

        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = readUint32(raf)
            val type = readFourCC(raf)
            val boxSize = if (size == 1L) readUint64(raf) else if (size == 0L) end - pos else size

            if (type == "trak") {
                val fps = parseMp4TrakForFrameRate(raf, pos + 8, boxSize - 8)
                if (fps != null && fps > 0f) return fps
            }
            if (boxSize < 8) break
            pos += boxSize
        }
        return null
    }

    private fun parseMp4TrakForFrameRate(
        raf: RandomAccessFile,
        dataPos: Long,
        dataSize: Long
    ): Float? {
        var isVideoTrak = false
        var timescale = 0L
        var sampleDelta = 0L

        fun scanBoxes(boxPos: Long, boxEnd: Long) {
            var pos = boxPos
            while (pos + 8 <= boxEnd) {
                raf.seek(pos)
                val size = readUint32(raf)
                val type = readFourCC(raf)
                val boxSize = if (size == 1L) readUint64(raf) else if (size == 0L) boxEnd - pos else size
                val payload = pos + 8
                val payloadSize = boxSize - 8

                when (type) {
                    "mdia", "minf", "stbl" -> scanBoxes(payload, payload + payloadSize)
                    "hdlr" -> {
                        raf.seek(payload + 8)
                        val handlerType = readFourCC(raf)
                        if (handlerType == "vide") isVideoTrak = true
                    }
                    "mdhd" -> {
                        raf.seek(payload)
                        val version = raf.read()
                        raf.skipBytes(3)
                        if (version == 1) raf.skipBytes(16) else raf.skipBytes(8)
                        timescale = readUint32(raf)
                    }
                    "stts" -> {
                        raf.seek(payload + 4)
                        val entryCount = readUint32(raf)
                        if (entryCount > 0) {
                            raf.skipBytes(4)
                            sampleDelta = readUint32(raf)
                        }
                    }
                }
                if (boxSize < 8) break
                pos += boxSize
            }
        }

        scanBoxes(dataPos, minOf(dataPos + dataSize, raf.length()))

        if (isVideoTrak && timescale > 0L && sampleDelta > 0L) {
            val fps = timescale.toFloat() / sampleDelta.toFloat()
            return VideoHelper.snapToStandardFrameRate(fps)
        }
        return null
    }

    private fun extractMatroskaChapters(file: File): List<RawChapter> {
        val chapters = mutableListOf<RawChapter>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLength = raf.length()
                var chaptersHeaderPos = -1L
                var segmentDataOffset = -1L

                var pos = 0L
                val maxScan = minOf(fileLength, 8 * 1024 * 1024L)

                while (pos < maxScan) {
                    raf.seek(pos)
                    val id = readEbmlId(raf) ?: break
                    val size = readEbmlVint(raf) ?: break
                    val dataPos = raf.filePointer

                    if (id == 0x18538067L) { // Segment
                        segmentDataOffset = dataPos
                        pos = dataPos
                        continue
                    }

                    if (id == 0x114D9B74L) { // SeekHead
                        val seekChaptersPos = parseSeekHeadForChapters(raf, dataPos, size, segmentDataOffset)
                        if (seekChaptersPos > 0L) {
                            chaptersHeaderPos = seekChaptersPos
                        }
                    } else if (id == 0x1043A770L) { // Chapters
                        chaptersHeaderPos = pos
                        break
                    } else if (id == 0x1F43B675L) { // Cluster
                        break
                    }

                    pos = dataPos + size
                }

                if (chaptersHeaderPos <= 0L && fileLength > 8 * 1024 * 1024L) {
                    var tailPos = maxOf(0L, fileLength - 2 * 1024 * 1024L)
                    while (tailPos < fileLength) {
                        raf.seek(tailPos)
                        val id = readEbmlId(raf) ?: break
                        val size = readEbmlVint(raf) ?: break
                        if (id == 0x1043A770L) {
                            chaptersHeaderPos = tailPos
                            break
                        }
                        tailPos = raf.filePointer + size
                    }
                }

                if (chaptersHeaderPos >= 0L && chaptersHeaderPos < fileLength) {
                    parseMatroskaChaptersAtom(raf, chaptersHeaderPos, chapters)
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "UsbContainerParser: Error extracting Matroska chapters")
        }
        return chapters.sortedBy { it.startTicks }
    }

    private fun parseSeekHeadForChapters(
        raf: RandomAccessFile,
        dataPos: Long,
        size: Long,
        segmentDataOffset: Long
    ): Long {
        var chaptersPos = -1L
        val end = minOf(dataPos + size, raf.length())
        var pos = dataPos
        while (pos < end) {
            raf.seek(pos)
            val id = readEbmlId(raf) ?: break
            val elemSize = readEbmlVint(raf) ?: break
            val elemData = raf.filePointer

            if (id == 0x4DBBL) { // Seek
                var seekId = 0L
                var seekPos = -1L
                var seekChildPos = elemData
                val seekEnd = elemData + elemSize

                while (seekChildPos < seekEnd) {
                    raf.seek(seekChildPos)
                    val childId = readEbmlId(raf) ?: break
                    val childSize = readEbmlVint(raf) ?: break
                    val childData = raf.filePointer

                    if (childId == 0x53ABL) { // SeekID
                        seekId = readEbmlUint(raf, childSize)
                    } else if (childId == 0x53ACL) { // SeekPosition
                        seekPos = readEbmlUint(raf, childSize)
                    }
                    seekChildPos = childData + childSize
                }

                if (seekId == 0x1043A770L && seekPos >= 0 && segmentDataOffset >= 0) {
                    chaptersPos = segmentDataOffset + seekPos
                    break
                }
            }
            pos = elemData + elemSize
        }
        return chaptersPos
    }

    private fun parseMatroskaChaptersAtom(
        raf: RandomAccessFile,
        chaptersHeaderPos: Long,
        output: MutableList<RawChapter>
    ) {
        raf.seek(chaptersHeaderPos)
        val chaptersId = readEbmlId(raf) ?: return
        if (chaptersId != 0x1043A770L) return
        val chaptersSize = readEbmlVint(raf) ?: return
        val chaptersEnd = raf.filePointer + chaptersSize

        fun parseAtom(atomDataPos: Long, atomSize: Long) {
            val atomEnd = atomDataPos + atomSize
            var curPos = atomDataPos
            var startNanos = -1L
            var chapterTitle = ""

            while (curPos < atomEnd) {
                raf.seek(curPos)
                val id = readEbmlId(raf) ?: break
                val size = readEbmlVint(raf) ?: break
                val dataPos = raf.filePointer

                when (id) {
                    0x91L -> { // ChapterTimeStart
                        startNanos = readEbmlUint(raf, size)
                    }
                    0x80L -> { // ChapterDisplay
                        var dispPos = dataPos
                        val dispEnd = dataPos + size
                        while (dispPos < dispEnd) {
                            raf.seek(dispPos)
                            val dId = readEbmlId(raf) ?: break
                            val dSize = readEbmlVint(raf) ?: break
                            val dData = raf.filePointer
                            if (dId == 0x85L) { // ChapterString
                                chapterTitle = readEbmlString(raf, dSize)
                            }
                            dispPos = dData + dSize
                        }
                    }
                    0xB6L -> { // Nested ChapterAtom
                        parseAtom(dataPos, size)
                    }
                }
                curPos = dataPos + size
            }

            if (startNanos >= 0L) {
                val startTicks = startNanos / 100L // 1 tick = 100 ns
                output.add(RawChapter(startTicks, chapterTitle))
            }
        }

        var pos = raf.filePointer
        while (pos < chaptersEnd) {
            raf.seek(pos)
            val id = readEbmlId(raf) ?: break
            val size = readEbmlVint(raf) ?: break
            val dataPos = raf.filePointer

            if (id == 0x45B9L) { // EditionEntry
                var eePos = dataPos
                val eeEnd = dataPos + size
                while (eePos < eeEnd) {
                    raf.seek(eePos)
                    val eId = readEbmlId(raf) ?: break
                    val eSize = readEbmlVint(raf) ?: break
                    val eData = raf.filePointer
                    if (eId == 0xB6L) { // ChapterAtom
                        parseAtom(eData, eSize)
                    }
                    eePos = eData + eSize
                }
            } else if (id == 0xB6L) { // ChapterAtom directly in Chapters
                parseAtom(dataPos, size)
            }
            pos = dataPos + size
        }
    }

    private fun extractMp4Chapters(file: File): List<RawChapter> {
        val chapters = mutableListOf<RawChapter>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fileLen = raf.length()
                var pos = 0L

                while (pos + 8 <= fileLen) {
                    raf.seek(pos)
                    val size = readUint32(raf)
                    val type = readFourCC(raf)
                    val boxSize = if (size == 1L) readUint64(raf) else if (size == 0L) fileLen - pos else size

                    if (type == "moov") {
                        parseMp4ContainerBox(raf, pos + 8, boxSize - 8, chapters)
                        break
                    }
                    if (boxSize < 8) break
                    pos += boxSize
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "UsbContainerParser: Error extracting MP4 chapters")
        }
        return chapters.sortedBy { it.startTicks }
    }

    private fun parseMp4ContainerBox(
        raf: RandomAccessFile,
        dataPos: Long,
        dataSize: Long,
        output: MutableList<RawChapter>
    ) {
        val end = minOf(dataPos + dataSize, raf.length())
        var pos = dataPos

        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = readUint32(raf)
            val type = readFourCC(raf)
            val boxSize = if (size == 1L) readUint64(raf) else if (size == 0L) end - pos else size

            when (type) {
                "udta", "trak", "mdia", "minf", "stbl" -> {
                    parseMp4ContainerBox(raf, pos + 8, boxSize - 8, output)
                }
                "chpl" -> {
                    parseMp4ChplBox(raf, pos + 8, boxSize - 8, output)
                }
            }
            if (boxSize < 8) break
            pos += boxSize
        }
    }

    private fun parseMp4ChplBox(
        raf: RandomAccessFile,
        dataPos: Long,
        dataSize: Long,
        output: MutableList<RawChapter>
    ) {
        raf.seek(dataPos)
        val version = raf.read()
        raf.skipBytes(3)

        val count = if (version == 1) {
            raf.skipBytes(4)
            readUint32(raf).toInt()
        } else {
            readUint32(raf).toInt()
        }

        for (i in 0 until count) {
            if (raf.filePointer >= dataPos + dataSize) break
            val startTime100ns = readUint64(raf)
            val titleLen = raf.read()
            if (titleLen <= 0) continue
            val titleBytes = ByteArray(titleLen)
            raf.readFully(titleBytes)
            val title = String(titleBytes, Charsets.UTF_8).trim()
            output.add(RawChapter(startTime100ns, title))
        }
    }

    private fun readEbmlId(raf: RandomAccessFile): Long? {
        if (raf.filePointer >= raf.length()) return null
        val b0 = raf.read()
        if (b0 == -1) return null
        var mask = 0x80
        var length = 1
        while (length <= 8 && (b0 and mask) == 0) {
            mask = mask ushr 1
            length++
        }
        if (length > 8) return null
        var id = b0.toLong()
        for (i in 2..length) {
            val b = raf.read()
            if (b == -1) return null
            id = (id shl 8) or (b.toLong() and 0xFFL)
        }
        return id
    }

    private fun readEbmlVint(raf: RandomAccessFile): Long? {
        if (raf.filePointer >= raf.length()) return null
        val b0 = raf.read()
        if (b0 == -1) return null
        var mask = 0x80
        var length = 1
        while (length <= 8 && (b0 and mask) == 0) {
            mask = mask ushr 1
            length++
        }
        if (length > 8) return null
        var value = (b0 and (mask - 1)).toLong()
        for (i in 2..length) {
            val b = raf.read()
            if (b == -1) return null
            value = (value shl 8) or (b.toLong() and 0xFFL)
        }
        return value
    }

    private fun readEbmlUint(raf: RandomAccessFile, size: Long): Long {
        var value = 0L
        for (i in 0 until size.toInt()) {
            val b = raf.read()
            if (b == -1) break
            value = (value shl 8) or (b.toLong() and 0xFFL)
        }
        return value
    }

    private fun readEbmlString(raf: RandomAccessFile, size: Long): String {
        val bytes = ByteArray(size.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8).trim('\u0000', ' ', '\t', '\n', '\r')
    }

    private fun readUint32(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 == -1 || b1 == -1 || b2 == -1 || b3 == -1) return 0L
        return ((b0.toLong() and 0xFFL) shl 24) or
                ((b1.toLong() and 0xFFL) shl 16) or
                ((b2.toLong() and 0xFFL) shl 8) or
                (b3.toLong() and 0xFFL)
    }

    private fun readUint64(raf: RandomAccessFile): Long {
        val hi = readUint32(raf)
        val lo = readUint32(raf)
        return (hi shl 32) or (lo and 0xFFFFFFFFL)
    }

    private fun readFourCC(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }
}

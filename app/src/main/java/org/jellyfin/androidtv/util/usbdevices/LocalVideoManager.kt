@file:OptIn(UnstableApi::class)
package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.ui.playback.VideoManager
import org.jellyfin.androidtv.ui.playback.getSubtitleMediaStreamCodec
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID

object LocalVideoManager {

    private object LocalProbeConfig {
        const val MAX_READS = 500
        const val MAX_PROBE_BYTES = 2 * 1024 * 1024L
        const val DEFAULT_VIDEO_WIDTH = 1920
        const val DEFAULT_VIDEO_HEIGHT = 1080
        const val DEFAULT_AUDIO_CHANNELS = 2
        const val DEFAULT_AUDIO_SAMPLE_RATE = 48000
        const val TICKS_PER_MS = 10_000L
    }

    private object MediaCodecHelper {
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
    }

    private object AudioChannelHelper {
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
    }

    private fun Context.getFileProviderUri(file: File): String {
        return try {
            FileProvider.getUriForFile(this, "$packageName.provider", file).toString()
        } catch (_: Exception) {
            Uri.fromFile(file).toString()
        }
    }

    @UnstableApi
    fun configureAndPlayLocal(videoManager: VideoManager, streamInfo: StreamInfo) {
        val tStart = System.currentTimeMillis()
        Timber.d("Benchmark: configureAndPlayLocal started (0ms)")
        val path = streamInfo.mediaUrl ?: return

        val player = videoManager.mExoPlayer ?: return

        try {
            val subtitleConfigurations = mutableListOf<MediaItem.SubtitleConfiguration>()
            streamInfo.mediaSource?.mediaStreams?.forEach { mediaStream ->
                if (mediaStream.type == MediaStreamType.SUBTITLE &&
                    mediaStream.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL &&
                    mediaStream.deliveryUrl != null
                ) {
                    val url = mediaStream.deliveryUrl ?: ""
                    if (url.startsWith("file:") || url.startsWith("content:") || url.startsWith("/mnt/") || url.startsWith("/storage/")) {
                        runCatching {
                            val subtitleUri = if (url.startsWith("file:") || url.startsWith("content:")) {
                                url.toUri()
                            } else {
                                Uri.fromFile(File(url))
                            }
                            val subtitleConfiguration = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                                .setId("JF_EXTERNAL:${mediaStream.index}")
                                .setMimeType(getSubtitleMediaStreamCodec(mediaStream))
                                .setLanguage(mediaStream.language)
                                .setLabel(mediaStream.displayTitle)
                                .build()
                            subtitleConfigurations.add(subtitleConfiguration)
                        }
                    } else {
                        Timber.w("UsbDebug: Ignoring non-local external subtitle URL: $url")
                    }
                }
            }

            val mediaItem = MediaItem.Builder()
                .setUri(path.toUri())
                .setSubtitleConfigurations(subtitleConfigurations)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            player.setAudioAttributes(audioAttributes, false)

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
            Timber.d("Benchmark: player.setMediaItem and prepare() triggered in ${System.currentTimeMillis() - tStart}ms")
        } catch (t: Throwable) {
            Timber.e(t, "LocalVideoManager: Error in configureAndPlayLocal")
        }
    }

    fun inspectAndBuildBaseItemDtoSync(context: Context, file: File): BaseItemDto {
        return runBlocking(Dispatchers.IO) {
            inspectAndBuildBaseItemDto(context, file)
        }
    }

    suspend fun inspectAndBuildBaseItemDto(context: Context, file: File): BaseItemDto = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray())
        val isVideo = UsbMediaHelper.isVideoFile(file)
        val mediaType = if (isVideo) MediaType.VIDEO else MediaType.AUDIO
        val containerExt = file.extension.ifBlank { "mkv" }

        val uriStr = context.getFileProviderUri(file)

        val streams = mutableListOf<MediaStream>()
        var durationMs = 0L

        var t1 = 0L
        var tEndTracks = 0L
        var t2 = 0L

        val formats = mutableListOf<Format>()

        try {
            val fileDataSource = FileDataSource()
            try {
                var dataSpec = DataSpec(Uri.fromFile(file))
                fileDataSource.open(dataSpec)
                t1 = System.currentTimeMillis()

                val subtitleParserFactory = DefaultSubtitleParserFactory()
                val flags = MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES or MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
                val extractor: Extractor = if (containerExt.equals("mkv", ignoreCase = true)) {
                    MatroskaExtractor(subtitleParserFactory, flags)
                } else {
                    val inputForSniff = DefaultExtractorInput(fileDataSource, 0, file.length())
                    val extractors = DefaultExtractorsFactory().createExtractors()
                    var found: Extractor? = null
                    for (e in extractors) {
                        try {
                            if (e.sniff(inputForSniff)) {
                                found = e
                                break
                            }
                        } catch (_: Exception) {}
                        inputForSniff.resetPeekPosition()
                    }
                    // Reset fileDataSource to position 0 after sniffing so extractor reads from offset 0
                    try {
                        fileDataSource.close()
                        fileDataSource.open(dataSpec)
                    } catch (e: Exception) {
                        Timber.w(e, "LocalVideoManager: Failed to reset FileDataSource position after sniffing")
                    }
                    found ?: MatroskaExtractor(subtitleParserFactory, flags)
                }

                var input = DefaultExtractorInput(fileDataSource, 0, file.length())
                var tracksEnded = false

                val output = object : ExtractorOutput {
                    @UnstableApi
                    override fun track(id: Int, type: Int): TrackOutput {
                        return object : TrackOutput {
                            override fun format(format: Format) {
                                formats.add(format)
                            }
                            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int = length
                            override fun sampleData(data: ParsableByteArray, length: Int) {}
                            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
                            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int = length
                            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {}
                        }
                    }
                    @UnstableApi
                    override fun endTracks() {
                        tracksEnded = true
                        tEndTracks = System.currentTimeMillis()
                    }
                    @UnstableApi
                    override fun seekMap(seekMap: SeekMap) {
                        if (seekMap.durationUs != C.TIME_UNSET) {
                            durationMs = seekMap.durationUs / 1000L
                        }
                    }
                }

                extractor.init(output)

                val positionHolder = PositionHolder()
                var readResult = Extractor.RESULT_CONTINUE
                var maxReads = LocalProbeConfig.MAX_READS

                while (readResult != Extractor.RESULT_END_OF_INPUT && maxReads > 0) {
                    coroutineContext.ensureActive()
                    readResult = extractor.read(input, positionHolder)
                    if (tracksEnded && durationMs > 0L) {
                        break
                    }
                    if (input.position > LocalProbeConfig.MAX_PROBE_BYTES) {
                        break
                    }
                    if (readResult == Extractor.RESULT_SEEK) {
                        if (formats.isNotEmpty() || tracksEnded) {
                            break
                        }
                        try {
                            fileDataSource.close()
                            dataSpec = DataSpec(Uri.fromFile(file), positionHolder.position, C.LENGTH_UNSET.toLong())
                            fileDataSource.open(dataSpec)
                            val remainingLength = if (file.length() > positionHolder.position) file.length() - positionHolder.position else C.LENGTH_UNSET.toLong()
                            input = DefaultExtractorInput(fileDataSource, positionHolder.position, remainingLength)
                        } catch (e: Exception) {
                            Timber.w(e, "LocalVideoManager: Error seeking FileDataSource")
                            break
                        }
                    }
                    maxReads--
                }
            } finally {
                try {
                    fileDataSource.close()
                } catch (_: Exception) {}
            }
            t2 = System.currentTimeMillis()

            var globalIndex = 0
            var nbV = 0
            var nbA = 0
            var nbS = 0

            for (format in formats) {
                val mime = format.sampleMimeType ?: continue
                val lang = format.language ?: "und"
                val displayName = getDisplayNameForLanguage(lang, context)

                val offsetMs = if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE && format.subsampleOffsetUs != 0L) {
                    format.subsampleOffsetUs / 1000L
                } else {
                    0L
                }
                if (offsetMs != 0L) {
                    Timber.d("UsbDebug: Track [$displayName] index=$globalIndex type=$mime offset detected: ${offsetMs}ms")
                }

                val isFormatDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0

                val mimeLower = mime.lowercase(Locale.ROOT)
                val isVideoTrack = mimeLower.startsWith("video/")
                val isAudioTrack = mimeLower.startsWith("audio/")
                val isSubtitleTrack = MediaCodecHelper.isSubtitleMime(mimeLower)

                when {
                    isVideoTrack -> {
                        nbV++
                        val codec = mimeLower.substringAfter('/')
                        var width = if (format.width != Format.NO_VALUE) format.width else LocalProbeConfig.DEFAULT_VIDEO_WIDTH
                        var height = if (format.height != Format.NO_VALUE) format.height else LocalProbeConfig.DEFAULT_VIDEO_HEIGHT

                        if (format.width == Format.NO_VALUE || format.height == Format.NO_VALUE) {
                            val fallbackDim = extractFallbackVideoDimensions(file)
                            if (fallbackDim != null) {
                                width = fallbackDim.first
                                height = fallbackDim.second
                            }
                        }

                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0
                        val title = resolveTrackTitle(format, MediaStreamType.VIDEO, displayName, codec.uppercase(Locale.ROOT), 0, isForced)

                        streams.add(MediaStream(type = MediaStreamType.VIDEO, index = globalIndex++, codec = codec, width = width, height = height, bitRate = bitrate, isDefault = isFormatDefault || nbV == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false, title = title, language = lang))
                    }
                    isAudioTrack -> {
                        nbA++
                        val codec = MediaCodecHelper.normalizeAudioCodec(mimeLower)
                        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else LocalProbeConfig.DEFAULT_AUDIO_CHANNELS
                        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE
                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0

                        val title = resolveTrackTitle(format, MediaStreamType.AUDIO, displayName, codec.uppercase(Locale.ROOT), channels, isForced)

                        streams.add(MediaStream(type = MediaStreamType.AUDIO, index = globalIndex++, codec = codec, channels = channels, sampleRate = sampleRate, bitRate = bitrate, language = lang, title = title, displayTitle = title, isDefault = isFormatDefault || nbA == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
                    }
                    isSubtitleTrack -> {
                        nbS++
                        val codec = MediaCodecHelper.normalizeSubtitleCodec(mimeLower)
                        val isForcedTrack = isForced || (format.label?.lowercase(Locale.ROOT)?.let { it.contains("forced") || it.contains("forcé") } == true)
                        val isSdhTrack = format.label?.lowercase(Locale.ROOT)?.contains("sdh") == true
                        val title = resolveTrackTitle(format, MediaStreamType.SUBTITLE, displayName, codec.uppercase(Locale.ROOT), 0, isForcedTrack, isSdhTrack)

                        streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = globalIndex++, codec = codec, language = lang, title = title, displayTitle = title, isDefault = isFormatDefault, isForced = isForcedTrack, isExternal = false, isHearingImpaired = isSdhTrack, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = false, deliveryMethod = SubtitleDeliveryMethod.EMBED))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LocalVideoManager: Media3 extraction error for ${file.name}")
        }

        val runTimeTicks = if (durationMs > 0) durationMs * LocalProbeConfig.TICKS_PER_MS else null

        // Fallbacks
        if (isVideo && streams.none { it.type == MediaStreamType.VIDEO }) {
            var width = LocalProbeConfig.DEFAULT_VIDEO_WIDTH
            var height = LocalProbeConfig.DEFAULT_VIDEO_HEIGHT
            val fallbackDim = extractFallbackVideoDimensions(file)
            if (fallbackDim != null) {
                width = fallbackDim.first
                height = fallbackDim.second
            }
            streams.add(0, MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, width = width, height = height, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
        }
        if (streams.none { it.type == MediaStreamType.AUDIO }) {
            val fallbackTitle = "Stereo - AAC"
            streams.add(MediaStream(type = MediaStreamType.AUDIO, index = streams.size, codec = "aac", channels = LocalProbeConfig.DEFAULT_AUDIO_CHANNELS, sampleRate = LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE, language = "und", title = fallbackTitle, displayTitle = fallbackTitle, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
        }

        // Sidecars (external subtitles) - inserted before embedded subtitles so external subtitles appear first
        val sidecars = UsbMediaHelper.findSidecarSubtitles(file)
        val externalSubStreams = sidecars.map { subFile ->
            val subUriStr = context.getFileProviderUri(subFile)
            val meta = parseExternalSubtitleMeta(subFile, file, context = context)
            MediaStream(type = MediaStreamType.SUBTITLE, index = -1, codec = subFile.extension, language = meta.language ?: "und", title = meta.displayTitle, displayTitle = meta.displayTitle, isDefault = false, isForced = meta.isForced, isExternal = true, isHearingImpaired = meta.isHearingImpaired, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = subUriStr)
        }
        val firstSubIdx = streams.indexOfFirst { it.type == MediaStreamType.SUBTITLE }
        if (firstSubIdx >= 0) {
            streams.addAll(firstSubIdx, externalSubStreams)
        } else {
            streams.addAll(externalSubStreams)
        }

        // Re-index streams sequentially so index matches position in list
        val finalStreams = streams.mapIndexed { idx, stream ->
            if (stream.index != idx) stream.copy(index = idx) else stream
        }

        val openTime = t1 - t0
        val tracksTime = if (tEndTracks > 0) tEndTracks - t1 else -1
        val loopTime = t2 - t1
        val totalTime = System.currentTimeMillis() - t0

        Timber.i("UsbDebug: Timings -> Open=${openTime}ms, TracksEnd=${tracksTime}ms, Loop=${loopTime}ms, Total=${totalTime}ms")

        val mediaSource = MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = "local_src_" + file.name.hashCode(),
            path = uriStr,
            type = MediaSourceType.DEFAULT,
            container = containerExt,
            name = file.name,
            isRemote = false,
            supportsDirectPlay = true,
            supportsDirectStream = true,
            supportsTranscoding = false,
            hasSegments = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            isInfiniteStream = false,
            runTimeTicks = runTimeTicks,
            mediaStreams = finalStreams
        )

        val userData = UserItemDataDto(
            itemId = id,
            playbackPositionTicks = 0L,
            playCount = 0,
            isFavorite = false,
            played = false,
            key = id.toString()
        )

        val chapters = LocalChapterExtractor.extractChapters(file, context).takeIf { it.isNotEmpty() }

        BaseItemDto(
            id = id,
            name = file.nameWithoutExtension,
            type = if (isVideo) BaseItemKind.MOVIE else BaseItemKind.AUDIO,
            mediaType = mediaType,
            mediaSources = listOf(mediaSource),
            locationType = LocationType.FILE_SYSTEM,
            path = file.absolutePath,
            runTimeTicks = runTimeTicks,
            userData = userData,
            chapters = chapters,
            canDownload = false,
            isFolder = false
        )
    }

    fun buildMinimalBaseItemDto(context: Context, file: File): BaseItemDto {
        val id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray())
        val isVideo = UsbMediaHelper.isVideoFile(file)
        val mediaType = if (isVideo) MediaType.VIDEO else MediaType.AUDIO
        val containerExt = file.extension.ifBlank { "mkv" }

        val uriStr = context.getFileProviderUri(file)

        val streams = mutableListOf(
            MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, width = LocalProbeConfig.DEFAULT_VIDEO_WIDTH, height = LocalProbeConfig.DEFAULT_VIDEO_HEIGHT, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false),
            MediaStream(type = MediaStreamType.AUDIO, index = 1, codec = "aac", channels = LocalProbeConfig.DEFAULT_AUDIO_CHANNELS, sampleRate = LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE, language = "und", title = "Stereo - AAC", displayTitle = "Stereo - AAC", isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false)
        )

        val sidecars = UsbMediaHelper.findSidecarSubtitles(file)
        sidecars.forEach { subFile ->
            val subUriStr = context.getFileProviderUri(subFile)
            val meta = parseExternalSubtitleMeta(subFile, file, context = context)
            streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = streams.size, codec = subFile.extension, language = meta.language ?: "und", title = meta.displayTitle, displayTitle = meta.displayTitle, isDefault = false, isForced = meta.isForced, isExternal = true, isHearingImpaired = meta.isHearingImpaired, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = subUriStr))
        }

        val finalStreams = streams.mapIndexed { idx, stream ->
            if (stream.index != idx) stream.copy(index = idx) else stream
        }

        val mediaSource = MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = "local_src_" + file.name.hashCode(),
            path = uriStr,
            type = MediaSourceType.DEFAULT,
            container = containerExt,
            name = file.name,
            isRemote = false,
            supportsDirectPlay = true,
            supportsDirectStream = true,
            supportsTranscoding = false,
            hasSegments = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            isInfiniteStream = false,
            mediaStreams = finalStreams
        )

        return BaseItemDto(
            id = id,
            name = file.nameWithoutExtension,
            type = if (isVideo) BaseItemKind.MOVIE else BaseItemKind.AUDIO,
            mediaType = mediaType,
            mediaSources = listOf(mediaSource),
            locationType = LocationType.FILE_SYSTEM,
            path = file.absolutePath,
            userData = UserItemDataDto(itemId = id, playbackPositionTicks = 0L, playCount = 0, isFavorite = false, played = false, key = id.toString()),
            canDownload = false,
            isFolder = false
        )
    }

    private fun extractFallbackVideoDimensions(file: File): Pair<Int, Int>? {
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

    fun formatSubtitleDisplayTitle(
        title: String?,
        languageDisplayName: String,
        isForced: Boolean,
        isHearingImpaired: Boolean,
        codecUpper: String,
        context: Context? = null
    ): String {
        val forcedTag = if (isForced) " - Forcé" else ""
        val sdhStr = context?.getString(R.string.indicator_subtitles_hearing_impaired) ?: "SDH"
        val sdhTag = if (isHearingImpaired) " - $sdhStr" else ""

        return if (!title.isNullOrBlank() && title != languageDisplayName) {
            "$title - $languageDisplayName$forcedTag$sdhTag - $codecUpper"
        } else {
            "$languageDisplayName$forcedTag$sdhTag - $codecUpper"
        }
    }

    private fun resolveTrackTitle(
        format: Format,
        msType: MediaStreamType,
        languageDisplayName: String,
        codecUpper: String,
        channels: Int,
        isForced: Boolean,
        isHearingImpaired: Boolean = false,
        context: Context? = null
    ): String {
        val originalTitle = format.label
        val offsetMs = if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE && format.subsampleOffsetUs != 0L) {
            format.subsampleOffsetUs / 1000L
        } else {
            0L
        }
        val offsetTag = if (offsetMs != 0L) " (Offset ${offsetMs}ms)" else ""

        val baseTitle = when (msType) {
            MediaStreamType.AUDIO -> {
                val channelLayout = AudioChannelHelper.formatChannelLayout(channels)
                val layoutSuffix = if (channelLayout.isNotBlank()) " $channelLayout" else ""
                if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) {
                    "$originalTitle - $languageDisplayName - $codecUpper$layoutSuffix"
                } else {
                    "$languageDisplayName - $codecUpper$layoutSuffix"
                }
            }
            MediaStreamType.SUBTITLE -> {
                val cleanTitle = if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) originalTitle else null
                formatSubtitleDisplayTitle(cleanTitle, languageDisplayName, isForced, isHearingImpaired, codecUpper, context)
            }
            else -> {
                if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) {
                    "$originalTitle - $languageDisplayName"
                } else {
                    languageDisplayName
                }
            }
        }

        return "$baseTitle$offsetTag"
    }

    fun getDisplayNameForLanguage(langCode: String?, context: Context? = null): String {
        val unknownStr = context?.getString(R.string.lbl_bracket_unknown) ?: "Unknown"
        if (langCode.isNullOrBlank()) return unknownStr

        val normalizedLang = when (val lower = langCode.lowercase(Locale.ROOT)) {
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            "eng" -> "en"
            "spa" -> "es"
            "ita" -> "it"
            "jpn" -> "ja"
            "chi", "zho" -> "zh"
            "rus" -> "ru"
            "por" -> "pt"
            "dut", "nld" -> "nl"
            "pol" -> "pl"
            "kor" -> "ko"
            "swe" -> "sv"
            "nor" -> "no"
            "fin" -> "fi"
            "dan" -> "da"
            "ara" -> "ar"
            "hin" -> "hi"
            "tur" -> "tr"
            "ukr" -> "uk"
            "cze", "ces" -> "cs"
            "gre", "ell" -> "el"
            "hun" -> "hu"
            "ron", "rum" -> "ro"
            else -> lower
        }

        val locale = Locale.forLanguageTag(normalizedLang)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        return if (display.isNotBlank() && display != normalizedLang && display != langCode) {
            display.replaceFirstChar { it.uppercase() }
        } else {
            runCatching { Locale.Builder().setLanguage(normalizedLang).build().getDisplayLanguage(Locale.getDefault()) }
                .getOrNull()?.replaceFirstChar { it.uppercase() } ?: langCode
        }
    }

    fun cleanSubtitleTitleAndLanguage(
        rawTitle: String?,
        videoName: String?,
        fallbackLang: String? = null,
        context: Context? = null
    ): Pair<String, String?> {
        val defaultSubtitleName = context?.getString(R.string.pref_subtitles) ?: "Subtitle"
        if (rawTitle.isNullOrBlank()) {
            val langName = fallbackLang?.let { getDisplayNameForLanguage(it, context) } ?: defaultSubtitleName
            return Pair(langName, fallbackLang)
        }

        var clean = rawTitle.substringBeforeLast('.').ifBlank { rawTitle }.trim()

        if (!videoName.isNullOrBlank()) {
            val videoBase = videoName.substringBeforeLast('.').trim()
            if (videoBase.isNotEmpty() && clean.startsWith(videoBase, ignoreCase = true)) {
                clean = clean.substring(videoBase.length)
            }
        }

        clean = clean.dropWhile { it in ".-_ " }

        var detectedLang = fallbackLang
        val lastDotIndex = clean.lastIndexOf('.')

        if (lastDotIndex != -1) {
            val segment = clean.substring(lastDotIndex + 1).trim()
            if (segment.length in 2..3 && segment.all { it.isLetter() }) {
                detectedLang = segment.lowercase(Locale.ROOT)
                clean = clean.substring(0, lastDotIndex).trim('.', '-', '_', ' ')
            }
        } else if (clean.length in 2..3 && clean.all { it.isLetter() }) {
            detectedLang = clean.lowercase(Locale.ROOT)
            clean = ""
        }

        val finalTitle = clean.ifBlank {
            detectedLang?.let { getDisplayNameForLanguage(it, context) } ?: defaultSubtitleName
        }

        return Pair(finalTitle, detectedLang)
    }

    data class SubtitleMeta(
        val displayTitle: String,
        val language: String?,
        val isForced: Boolean,
        val isHearingImpaired: Boolean
    )

    fun parseExternalSubtitleMeta(
        subFile: File,
        videoFile: File,
        context: Context? = null
    ): SubtitleMeta {
        val rawName = subFile.name
        val lower = rawName.lowercase(Locale.ROOT)

        val isForced = lower.contains("forced") || lower.contains("forcé")
        val isHearingImpaired = lower.contains("sdh")

        val (rawCleanTitle, lang) = cleanSubtitleTitleAndLanguage(rawName, videoFile.name, context = context)
        val langDisplayName = getDisplayNameForLanguage(lang, context)
        val codecUpper = subFile.extension.uppercase(Locale.ROOT)

        val cleanTitle = if (rawCleanTitle.isNotBlank() && !rawCleanTitle.equals(langDisplayName, ignoreCase = true) && !rawCleanTitle.equals(subFile.extension, ignoreCase = true)) {
            rawCleanTitle
        } else {
            null
        }

        val displayTitle = formatSubtitleDisplayTitle(
            title = cleanTitle,
            languageDisplayName = langDisplayName,
            isForced = isForced,
            isHearingImpaired = isHearingImpaired,
            codecUpper = codecUpper,
            context = context
        )

        return SubtitleMeta(
            displayTitle = displayTitle,
            language = lang,
            isForced = isForced,
            isHearingImpaired = isHearingImpaired
        )
    }

    fun buildLocalStreamInfo(item: BaseItemDto): StreamInfo {
        val file = File(item.path ?: "")
        val fileUriStr = Uri.fromFile(file).toString()
        val containerExt = file.extension.ifBlank { "mkv" }

        val mediaSource = item.mediaSources?.firstOrNull() ?: MediaSourceInfo(
            protocol = MediaProtocol.FILE,
            id = "local_src_" + file.name.hashCode(),
            path = fileUriStr,
            type = MediaSourceType.DEFAULT,
            container = containerExt,
            name = file.name,
            isRemote = false,
            supportsDirectPlay = true,
            supportsDirectStream = true,
            supportsTranscoding = false,
            hasSegments = false,
            requiresOpening = false,
            requiresClosing = false,
            requiresLooping = false,
            supportsProbing = false,
            readAtNativeFramerate = false,
            ignoreDts = false,
            ignoreIndex = false,
            genPtsInput = false,
            transcodingSubProtocol = MediaStreamProtocol.HTTP,
            isInfiniteStream = false
        )

        val safeMediaSource = mediaSource.copy(
            id = mediaSource.id ?: ("local_src_" + file.name.hashCode()),
            path = fileUriStr,
            protocol = MediaProtocol.FILE,
            container = mediaSource.container ?: containerExt,
            supportsDirectPlay = true,
            supportsDirectStream = true,
            supportsTranscoding = false,
            isRemote = false,
            isInfiniteStream = false,
            mediaStreams = mediaSource.mediaStreams ?: item.mediaStreams
        )

        return StreamInfo().apply {
            itemId = item.id
            this.mediaSource = safeMediaSource
            mediaUrl = fileUriStr
            playMethod = PlayMethod.DIRECT_PLAY
            container = containerExt
            playSessionId = "local_session_" + file.name.hashCode()
            runTimeTicks = item.runTimeTicks
        }
    }

    private object LocalChapterExtractor {

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
                            Timber.w(e, "LocalVideoManager: Error generating chapter thumbnail $index for ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        Timber.w(e, "LocalVideoManager: Error in thumbnail generation for ${file.name}")
                    }
                } finally {
                    runCatching { retriever?.release() }
                }
            }
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
                Timber.w(e, "LocalVideoManager: Error extracting Matroska chapters")
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
                Timber.w(e, "LocalVideoManager: Error extracting MP4 chapters")
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
}

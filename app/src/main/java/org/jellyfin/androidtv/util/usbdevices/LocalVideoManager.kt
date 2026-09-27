@file:OptIn(UnstableApi::class)
package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.ui.playback.VideoManager
import org.jellyfin.androidtv.ui.playback.getSubtitleMediaStreamCodec
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
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
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType
import timber.log.Timber
import java.io.File
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

    fun getDisplayNameForLanguage(langCode: String?, context: Context? = null): String =
        UsbSubtitleUtils.getDisplayNameForLanguage(langCode, context)

    fun resolveTrackTitle(
        format: Format,
        msType: MediaStreamType,
        languageDisplayName: String,
        codecUpper: String,
        channels: Int,
        isForced: Boolean,
        isHearingImpaired: Boolean = false,
        context: Context? = null
    ): String = UsbSubtitleUtils.resolveTrackTitle(format, msType, languageDisplayName, codecUpper, channels, isForced, isHearingImpaired, context)

    fun parseExternalSubtitleMeta(
        subFile: File,
        videoFile: File,
        context: Context? = null
    ): UsbSubtitleUtils.SubtitleMeta = UsbSubtitleUtils.parseExternalSubtitleMeta(subFile, videoFile, context)

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
                            val fallbackDim = VideoHelper.extractFallbackDimensions(file)
                            if (fallbackDim != null) {
                                width = fallbackDim.first
                                height = fallbackDim.second
                            }
                        }

                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0
                        val probedMeta = VideoHelper.probeVideoMetadata(file)
                        val rawFrameRate = if (format.frameRate > 0f) format.frameRate else probedMeta.frameRate
                        val (videoRangeTypeVal, videoRangeVal) = VideoHelper.detectVideoRange(format)
                        val aspectRatioVal = VideoHelper.calculateAspectRatio(width, height, format)
                        val isInterlacedVal = probedMeta.isInterlaced
                        val title = resolveTrackTitle(format, MediaStreamType.VIDEO, displayName, codec.uppercase(Locale.ROOT), 0, isForced)

                        Timber.i("UsbDebug: Video Inspection complete for %s -> realFrameRate=%s, resolution=%dx%d", file.name, rawFrameRate, width, height)
                        streams.add(MediaStream(type = MediaStreamType.VIDEO, index = globalIndex++, codec = codec, width = width, height = height, bitRate = bitrate, realFrameRate = rawFrameRate, averageFrameRate = rawFrameRate, videoRange = videoRangeVal, videoRangeType = videoRangeTypeVal, aspectRatio = aspectRatioVal, profile = format.codecs, isInterlaced = isInterlacedVal, isDefault = isFormatDefault || nbV == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isTextSubtitleStream = false, supportsExternalStream = false, title = title, language = lang))
                    }
                    isAudioTrack -> {
                        nbA++
                        val codec = MediaCodecHelper.normalizeAudioCodec(mimeLower)
                        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else LocalProbeConfig.DEFAULT_AUDIO_CHANNELS
                        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE
                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0
                        val channelLayoutVal = AudioChannelHelper.formatChannelLayout(channels).ifBlank { null }
                        val audioProfileVal = AudioChannelHelper.detectAudioProfile(format)

                        val title = resolveTrackTitle(format, MediaStreamType.AUDIO, displayName, codec.uppercase(Locale.ROOT), channels, isForced)

                        streams.add(MediaStream(type = MediaStreamType.AUDIO, index = globalIndex++, codec = codec, channels = channels, sampleRate = sampleRate, bitRate = bitrate, channelLayout = channelLayoutVal, profile = audioProfileVal, language = lang, title = title, displayTitle = title, isDefault = isFormatDefault || nbA == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
                    }
                    isSubtitleTrack -> {
                        nbS++
                        val codec = MediaCodecHelper.normalizeSubtitleCodec(mimeLower)
                        val isForcedTrack = isForced || (format.label?.lowercase(Locale.ROOT)?.let { it.contains("forced") || it.contains("forcé") } == true)
                        val isSdhTrack = format.label?.lowercase(Locale.ROOT)?.contains("sdh") == true
                        val isTextSub = MediaCodecHelper.isTextSubtitle(codec, mimeLower)
                        val title = resolveTrackTitle(format, MediaStreamType.SUBTITLE, displayName, codec.uppercase(Locale.ROOT), 0, isForcedTrack, isSdhTrack)

                        streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = globalIndex++, codec = codec, language = lang, title = title, displayTitle = title, isDefault = isFormatDefault, isForced = isForcedTrack, isExternal = false, isHearingImpaired = isSdhTrack, isInterlaced = false, isTextSubtitleStream = isTextSub, supportsExternalStream = false, deliveryMethod = SubtitleDeliveryMethod.EMBED))
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
            val fallbackDim = VideoHelper.extractFallbackDimensions(file)
            if (fallbackDim != null) {
                width = fallbackDim.first
                height = fallbackDim.second
            }
            val fallbackFrameRate = VideoHelper.probeVideoMetadata(file).frameRate
            streams.add(0, MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, width = width, height = height, realFrameRate = fallbackFrameRate, averageFrameRate = fallbackFrameRate, videoRange = VideoRange.SDR, videoRangeType = VideoRangeType.SDR, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
        }
        if (streams.none { it.type == MediaStreamType.AUDIO }) {
            val fallbackTitle = "Stereo - AAC"
            streams.add(MediaStream(type = MediaStreamType.AUDIO, index = streams.size, codec = "aac", channels = LocalProbeConfig.DEFAULT_AUDIO_CHANNELS, sampleRate = LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE, channelLayout = "Stereo", language = "und", title = fallbackTitle, displayTitle = fallbackTitle, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
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

        val chapters = UsbContainerParser.extractChapters(file, context).takeIf { it.isNotEmpty() }

        BaseItemDto(
            id = id,
            name = file.nameWithoutExtension,
            type = if (isVideo) BaseItemKind.MOVIE else BaseItemKind.AUDIO,
            mediaType = mediaType,
            mediaSources = listOf(mediaSource),
            mediaStreams = finalStreams,
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

        val fallbackFrameRate: Float? = null
        val streams = mutableListOf(
            MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, width = LocalProbeConfig.DEFAULT_VIDEO_WIDTH, height = LocalProbeConfig.DEFAULT_VIDEO_HEIGHT, realFrameRate = fallbackFrameRate, averageFrameRate = fallbackFrameRate, videoRange = VideoRange.SDR, videoRangeType = VideoRangeType.SDR, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false),
            MediaStream(type = MediaStreamType.AUDIO, index = 1, codec = "aac", channels = LocalProbeConfig.DEFAULT_AUDIO_CHANNELS, sampleRate = LocalProbeConfig.DEFAULT_AUDIO_SAMPLE_RATE, channelLayout = "Stereo", language = "und", title = "Stereo - AAC", displayTitle = "Stereo - AAC", isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false)
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
            mediaStreams = finalStreams,
            locationType = LocationType.FILE_SYSTEM,
            path = file.absolutePath,
            userData = UserItemDataDto(itemId = id, playbackPositionTicks = 0L, playCount = 0, isFavorite = false, played = false, key = id.toString()),
            canDownload = false,
            isFolder = false
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
            isInfiniteStream = false,
            mediaStreams = item.mediaStreams
        )

        val enrichedItemStreams = item.mediaSources?.firstOrNull()?.mediaStreams ?: item.mediaStreams
        val streamsToUse = enrichedItemStreams ?: mediaSource.mediaStreams

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
            mediaStreams = streamsToUse
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
}

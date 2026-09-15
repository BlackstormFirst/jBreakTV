@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import kotlinx.coroutines.Dispatchers
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
import timber.log.Timber
import java.io.File
import java.util.Locale
import java.util.UUID
import androidx.core.net.toUri
import kotlinx.coroutines.runBlocking

object LocalVideoManager {

	@UnstableApi
	fun configureAndPlayLocal(videoManager: VideoManager, streamInfo: StreamInfo) {
		val tStart = System.currentTimeMillis()
		Timber.d("AmorceBenchmark: configureAndPlayLocal démarré (0ms)")
		val path = streamInfo.mediaUrl ?: return

		//videoManager.resetPreparedState()
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
							val subtitleUri = url.toUri()
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

			// Delegate directly to official pipeline without overwriting AudioTrack or TrackSelector
			player.setMediaItem(mediaItem)
			player.prepare()
			player.setPlayWhenReady(true)
			Timber.d("AmorceBenchmark: player.setMediaItem et prepare() déclenchés en ${System.currentTimeMillis() - tStart}ms")
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

        val uriStr = try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file).toString()
        } catch (e: Exception) {
            Uri.fromFile(file).toString()
        }

        val streams = mutableListOf<MediaStream>()
        var durationMs = 0L
        var nbV = 0
        var nbA = 0
        var nbS = 0

        // Fast Media3 internal extraction (< 15ms)
        var t1 = 0L
        var tEndTracks = 0L
        var t2 = 0L

        val formats = mutableListOf<Format>()

        try {
            var fileDataSource = FileDataSource()
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
                        } catch (ignored: Exception) {}
                        inputForSniff.resetPeekPosition()
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
                var maxReads = 500

                while (readResult != Extractor.RESULT_END_OF_INPUT && maxReads > 0) {
                    readResult = extractor.read(input, positionHolder)
                    if (tracksEnded && durationMs > 0L) {
                        break
                    }
                    if (input.position > 2 * 1024 * 1024L) {
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
                            input = DefaultExtractorInput(fileDataSource, positionHolder.position, file.length() - positionHolder.position)
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
                } catch (ignored: Exception) {}
            }
            t2 = System.currentTimeMillis()

            var globalIndex = 0
            var nbV = 0
            var nbA = 0
            var nbS = 0

            for (format in formats) {
                val mime = format.sampleMimeType ?: continue
                val lang = format.language ?: "und"
                val displayName = getDisplayNameForLanguage(lang)

                val offsetMs = if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE && format.subsampleOffsetUs != 0L) {
                    format.subsampleOffsetUs / 1000L
                } else {
                    0L
                }
                if (offsetMs != 0L) {
                    Timber.d("UsbDebug: Track [$displayName] index=$globalIndex type=$mime offset detected: ${offsetMs}ms")
                }

                val isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0
                val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0

                val mimeLower = mime.lowercase(Locale.ROOT)
                val isVideoTrack = mimeLower.startsWith("video/")
                val isAudioTrack = mimeLower.startsWith("audio/")
                val isSubtitleTrack = mimeLower.startsWith("text/") ||
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

                when {
                    isVideoTrack -> {
                        nbV++
                        val codec = mimeLower.substringAfter('/')
                        val width = if (format.width != Format.NO_VALUE) format.width else 1920
                        val height = if (format.height != Format.NO_VALUE) format.height else 1080
                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0

                        val title = resolveTrackTitle(format, MediaStreamType.VIDEO, displayName, codec.uppercase(Locale.ROOT), 0, isForced)

                        streams.add(MediaStream(type = MediaStreamType.VIDEO, index = globalIndex++, codec = codec, width = width, height = height, bitRate = bitrate, isDefault = isDefault || nbV == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false, title = title, language = lang))
                    }
                    isAudioTrack -> {
                        nbA++
                        val rawCodec = mimeLower.substringAfter('/')
                        val codec = when {
                            rawCodec.contains("ac3") -> "ac3"
                            rawCodec.contains("eac3") -> "eac3"
                            rawCodec.contains("mp4a") || rawCodec.contains("aac") -> "aac"
                            rawCodec.contains("mpeg") || rawCodec.contains("mp3") -> "mp3"
                            rawCodec.contains("flac") -> "flac"
                            rawCodec.contains("dts") -> "dts"
                            else -> rawCodec
                        }
                        val channels = if (format.channelCount != Format.NO_VALUE) format.channelCount else 2
                        val sampleRate = if (format.sampleRate != Format.NO_VALUE) format.sampleRate else 48000
                        val bitrate = if (format.bitrate != Format.NO_VALUE) format.bitrate else 0

                        val title = resolveTrackTitle(format, MediaStreamType.AUDIO, displayName, codec.uppercase(Locale.ROOT), channels, isForced)

                        streams.add(MediaStream(type = MediaStreamType.AUDIO, index = globalIndex++, codec = codec, channels = channels, sampleRate = sampleRate, bitRate = bitrate, language = lang, title = title, displayTitle = title, isDefault = isDefault || nbA == 1, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
                    }
                    isSubtitleTrack -> {
                        nbS++
                        val rawCodec = mimeLower.substringAfter('/')
                        val codec = when {
                            rawCodec.contains("ssa") || rawCodec.contains("ass") -> "ass"
                            rawCodec.contains("subrip") || rawCodec.contains("srt") || rawCodec.contains("text") -> "subrip"
                            rawCodec.contains("vobsub") || rawCodec.contains("idx") -> "vobsub"
                            rawCodec.contains("pgs") || rawCodec.contains("presentation-graphic") -> "pgs"
                            else -> rawCodec
                        }

                        val title = resolveTrackTitle(format, MediaStreamType.SUBTITLE, displayName, codec.uppercase(Locale.ROOT), 0, isForced)

                        streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = globalIndex++, codec = codec, language = lang, title = title, displayTitle = title, isDefault = isDefault, isForced = isForced, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = false, deliveryMethod = SubtitleDeliveryMethod.EMBED))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "LocalVideoManager: Media3 extraction error for ${file.name}")
        }

        val runTimeTicks = if (durationMs > 0) durationMs * 10000L else null

        // Fallbacks
        if (isVideo && streams.none { it.type == MediaStreamType.VIDEO }) {
            streams.add(0, MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
        }
        if (streams.none { it.type == MediaStreamType.AUDIO }) {
            streams.add(MediaStream(type = MediaStreamType.AUDIO, index = streams.size, codec = "aac", channels = 2, sampleRate = 48000, language = "fre", title = "Français - AAC Stéréo", isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false))
        }

        // Sidecars
        val baseIndex = streams.size
        val sidecars = UsbMediaHelper.findSidecarSubtitles(file)
        sidecars.forEachIndexed { subIndex, subFile ->
            val subUriStr = try { FileProvider.getUriForFile(context, "${context.packageName}.provider", subFile).toString() } catch (e: Exception) { Uri.fromFile(subFile).toString() }
            streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = baseIndex + subIndex, codec = subFile.extension, language = "fre", title = "${subFile.nameWithoutExtension} (${subFile.extension.uppercase(Locale.ROOT)})", isDefault = false, isForced = false, isExternal = true, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = subUriStr))
        }

        val openTime = t1 - t0
        val tracksTime = if (tEndTracks > 0) tEndTracks - t1 else -1
        val loopTime = t2 - t1
        val totalTime = System.currentTimeMillis() - t0

        Timber.i("UsbDebug: Timings -> Open=${openTime}ms, TracksEnd=${tracksTime}ms, Loop=${loopTime}ms, Total=${totalTime}ms")

        val totalTracks = streams.size
        val minutes = (runTimeTicks ?: 0L) / 10000 / 1000 / 60

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
            mediaStreams = streams
        )

        val userData = UserItemDataDto(
            itemId = id,
            playbackPositionTicks = 0L,
            playCount = 0,
            isFavorite = false,
            played = false,
            key = id.toString()
        )

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
            canDownload = false,
            isFolder = false
        )
    }

    fun buildMinimalBaseItemDto(context: Context, file: File): BaseItemDto {
        val id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray())
        val isVideo = UsbMediaHelper.isVideoFile(file)
        val mediaType = if (isVideo) MediaType.VIDEO else MediaType.AUDIO
        val containerExt = file.extension.ifBlank { "mkv" }

        val uriStr = try {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file).toString()
        } catch (e: Exception) {
            Uri.fromFile(file).toString()
        }

        val streams = mutableListOf<MediaStream>(
            MediaStream(type = MediaStreamType.VIDEO, index = 0, codec = containerExt, isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false),
            MediaStream(type = MediaStreamType.AUDIO, index = 1, codec = "aac", language = "fre", title = "Français - AAC Stéréo", isDefault = true, isForced = false, isExternal = false, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = false, supportsExternalStream = false)
        )

        val baseIndex = streams.size
        val sidecars = UsbMediaHelper.findSidecarSubtitles(file)
        sidecars.forEachIndexed { subIndex, subFile ->
            val subUriStr = try { FileProvider.getUriForFile(context, "${context.packageName}.provider", subFile).toString() } catch (e: Exception) { Uri.fromFile(subFile).toString() }
            streams.add(MediaStream(type = MediaStreamType.SUBTITLE, index = baseIndex + subIndex, codec = subFile.extension, language = "fre", title = subFile.name, isDefault = false, isForced = false, isExternal = true, isHearingImpaired = false, isInterlaced = false, isTextSubtitleStream = true, supportsExternalStream = true, deliveryMethod = SubtitleDeliveryMethod.EXTERNAL, deliveryUrl = subUriStr))
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
            mediaStreams = streams
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

    private fun resolveTrackTitle(format: Format, msType: MediaStreamType, languageDisplayName: String, codecUpper: String, channels: Int, isForced: Boolean): String {
        val originalTitle = format.label
        val offsetMs = if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE && format.subsampleOffsetUs != 0L) {
            format.subsampleOffsetUs / 1000L
        } else {
            0L
        }
        val offsetTag = if (offsetMs != 0L) " (Offset ${offsetMs}ms)" else ""

        val baseTitle = if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) {
            "$originalTitle - $languageDisplayName"
        } else {
            when (msType) {
                MediaStreamType.AUDIO -> {
                    val channelLayout = when (channels) {
                        1 -> "Mono"
                        2 -> "Stéréo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "$channels ch"
                    }
                    "$languageDisplayName - $codecUpper $channelLayout"
                }
                MediaStreamType.SUBTITLE -> {
                    val forcedStr = if (isForced) " forced" else ""
                    "$languageDisplayName$forcedStr ($codecUpper)"
                }
                else -> languageDisplayName
            }
        }

        return "$baseTitle$offsetTag"
    }

    private fun getDisplayNameForLanguage(langCode: String?): String {
        if (langCode.isNullOrBlank()) return "Unknown"
        val locale = Locale.forLanguageTag(langCode)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        return if (display.isNotBlank() && display != langCode) {
            display.replaceFirstChar { it.uppercase() }
        } else {
            runCatching { Locale.Builder().setLanguage(langCode).build().getDisplayLanguage(Locale.getDefault()) }
                .getOrNull()?.replaceFirstChar { it.uppercase() } ?: langCode
        }
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
}

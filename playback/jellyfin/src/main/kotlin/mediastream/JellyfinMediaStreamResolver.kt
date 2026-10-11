package org.jellyfin.playback.jellyfin.mediastream

import android.net.Uri
import java.io.File
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.util.isLocalPath
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.playback.core.queue.preferredAudioIndex
import org.jellyfin.playback.core.queue.preferredSubtitleIndex
import org.jellyfin.playback.core.queue.preferredVideoIndex
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
	private val localItemInspector: (suspend (File) -> BaseItemDto)? = null,
	private val streamIndexResolver: ((BaseItemDto, MediaSourceInfo?) -> Triple<Int?, Int?, Int?>)? = null,
	private val maxBitrateProvider: (() -> Int)? = null,
	private val subtitlesBurnDuringTranscodeProvider: (() -> Boolean)? = null,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
	}

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		// Local USB/storage media handling
		if (isLocalPath(baseItem.path)) {
			val filePath = baseItem.path.orEmpty()
			val file = File(filePath)

			val itemToUse = if (baseItem.mediaStreams.isNullOrEmpty() || baseItem.runTimeTicks == null) {
				localItemInspector?.let { inspector -> runCatching { inspector.invoke(file) }.getOrNull() } ?: baseItem
			} else {
				baseItem
			}

			if (itemToUse != baseItem) {
				queueEntry.baseItem = itemToUse
			}

			val localUrl = if (filePath.startsWith("file:/") || filePath.startsWith("content:")) filePath else Uri.fromFile(file).toString()
			val containerExt = file.extension.ifBlank { "mkv" }
			val startTicks = queueEntry.baseItem?.userData?.playbackPositionTicks ?: 0L
			val clearedUserData = itemToUse.userData?.copy(playbackPositionTicks = 0L)
			queueEntry.baseItem = itemToUse.copy(userData = clearedUserData)

			val initialSource = itemToUse.mediaSources?.firstOrNull() ?: MediaSourceInfo(
				protocol = MediaProtocol.FILE,
				id = itemToUse.id.toString(),
				path = itemToUse.path,
				type = MediaSourceType.DEFAULT,
				isRemote = false,
				mediaStreams = itemToUse.mediaStreams,
				supportsDirectPlay = true,
				supportsDirectStream = true,
				supportsTranscoding = false,
				supportsProbing = false,
				requiresOpening = false,
				requiresClosing = false,
				requiresLooping = false,
				isInfiniteStream = false,
				ignoreIndex = false,
				ignoreDts = false,
				genPtsInput = false,
				readAtNativeFramerate = false,
				hasSegments = false,
				transcodingSubProtocol = MediaStreamProtocol.HTTP,
			)
			val (bestAudioIndex, bestSubIndex, bestVideoIndex) = streamIndexResolver?.invoke(itemToUse, initialSource) ?: Triple(null, null, null)
			if (queueEntry.preferredAudioIndex == null && bestAudioIndex != null) {
				queueEntry.preferredAudioIndex = bestAudioIndex
			}
			if (queueEntry.preferredSubtitleIndex == null && bestSubIndex != null) {
				queueEntry.preferredSubtitleIndex = bestSubIndex
			}
			if (queueEntry.preferredVideoIndex == null && bestVideoIndex != null) {
				queueEntry.preferredVideoIndex = bestVideoIndex
			}

			return PlayableMediaStream(
				identifier = "local_" + file.name.hashCode(),
				conversionMethod = MediaConversionMethod.None,
				container = MediaStreamContainer(format = containerExt),
				tracks = itemToUse.mediaStreams.orEmpty().mapNotNull { it.getMediaStreamTrack(api) },
				queueEntry = queueEntry,
				url = localUrl,
				startPositionTicks = startTicks,
			)
		}

		val mediaInfo = runCatching {
			getPlaybackInfo(queueEntry, queueEntry.mediaSourceId)
		}.getOrNull() ?: return null

		return when {
			// Direct play video
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.VIDEO -> {
				val (bestAudioIndex, bestSubIndex, bestVideoIndex) = streamIndexResolver?.invoke(baseItem, mediaInfo.mediaSource) ?: Triple(null, null, null)
				val audioIndexToUse = queueEntry.preferredAudioIndex ?: bestAudioIndex
				val subIndexToUse = queueEntry.preferredSubtitleIndex ?: bestSubIndex
				val videoIndexToUse = queueEntry.preferredVideoIndex ?: bestVideoIndex

				val rawUrl = api.videosApi.getVideoStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
				val finalUrl = rawUrl.withStreamIndexOverrides(audioIndexToUse, subIndexToUse, videoIndexToUse)

				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.None,
					url = finalUrl
				)
			}

			// Direct play audio
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.AUDIO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.audioApi.getAudioStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Remux (direct stream)
			mediaInfo.mediaSource.supportsDirectStream && mediaInfo.mediaSource.transcodingUrl != null -> {
				val rawUrl = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
				val (bestAudioIndex, bestSubIndex, bestVideoIndex) = streamIndexResolver?.invoke(baseItem, mediaInfo.mediaSource) ?: Triple(null, null, null)
				val audioIndexToUse = queueEntry.preferredAudioIndex ?: bestAudioIndex
				val subIndexToUse = queueEntry.preferredSubtitleIndex ?: bestSubIndex
				val videoIndexToUse = queueEntry.preferredVideoIndex ?: bestVideoIndex
				val finalUrl = rawUrl.withStreamIndexOverrides(audioIndexToUse, subIndexToUse, videoIndexToUse)

				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.Remux,
					url = finalUrl
				)
			}

			// Transcode
			mediaInfo.mediaSource.supportsTranscoding && mediaInfo.mediaSource.transcodingUrl != null -> {
				val rawUrl = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
				val (bestAudioIndex, bestSubIndex, bestVideoIndex) = streamIndexResolver?.invoke(baseItem, mediaInfo.mediaSource) ?: Triple(null, null, null)
				val audioIndexToUse = queueEntry.preferredAudioIndex ?: bestAudioIndex
				val subIndexToUse = queueEntry.preferredSubtitleIndex ?: bestSubIndex
				val videoIndexToUse = queueEntry.preferredVideoIndex ?: bestVideoIndex
				val finalUrl = rawUrl.withStreamIndexOverrides(audioIndexToUse, subIndexToUse, videoIndexToUse)

				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.Transcode,
					url = finalUrl
				)
			}

			// No compatible stream found
			else -> null
		}
	}

	private suspend fun getPlaybackInfo(
		queueEntry: QueueEntry,
		mediaSourceId: String? = null,
	): MediaInfo {
		val item = requireNotNull(queueEntry.baseItem)
		val initialSource = item.mediaSources?.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }
			?: item.mediaSources?.firstOrNull()

		val (bestAudioIndex, bestSubIndex, bestVideoIndex) = streamIndexResolver?.invoke(item, initialSource) ?: Triple(null, null, null)
		if (queueEntry.preferredAudioIndex == null && bestAudioIndex != null) {
			queueEntry.preferredAudioIndex = bestAudioIndex
		}
		if (queueEntry.preferredSubtitleIndex == null && bestSubIndex != null) {
			queueEntry.preferredSubtitleIndex = bestSubIndex
		}
		if (queueEntry.preferredVideoIndex == null && bestVideoIndex != null) {
			queueEntry.preferredVideoIndex = bestVideoIndex
		}
		val audioIndexToUse = queueEntry.preferredAudioIndex ?: bestAudioIndex
		val subIndexToUse = queueEntry.preferredSubtitleIndex ?: bestSubIndex

		val profile = deviceProfileBuilder()
		val maxBitrate = maxBitrateProvider?.invoke() ?: 0

		val allStreams = initialSource?.mediaStreams.orEmpty().ifEmpty { item.mediaStreams.orEmpty() }
		val requestedSubStream = allStreams.firstOrNull { it.index == subIndexToUse }
		val subCodec = requestedSubStream?.codec?.lowercase(java.util.Locale.ROOT).orEmpty()

		val isAssCodec = subCodec.contains("ass") || subCodec.contains("ssa")
		val isPgsCodec = subCodec.contains("pgs")
		val isAssSupportedDirect = isAssCodec && profile.subtitleProfiles.any {
			(it.format.equals("ass", ignoreCase = true) || it.format.equals("ssa", ignoreCase = true)) &&
				(it.method == SubtitleDeliveryMethod.EMBED || it.method == SubtitleDeliveryMethod.EXTERNAL)
		}
		val isPgsSupportedDirect = isPgsCodec && profile.subtitleProfiles.any {
			it.format?.lowercase(java.util.Locale.ROOT)?.contains("pgs") == true &&
				(it.method == SubtitleDeliveryMethod.EMBED || it.method == SubtitleDeliveryMethod.EXTERNAL)
		}

		val isUnsupportedSubCodec = (isAssCodec && !isAssSupportedDirect) ||
			(isPgsCodec && !isPgsSupportedDirect) ||
			subCodec.contains("dvd") || subCodec.contains("sub") || subCodec.contains("idx")

		val isEmbeddedSub = requestedSubStream != null && !requestedSubStream.isExternal && subIndexToUse != null && subIndexToUse >= 0
		val burnInPreference = subtitlesBurnDuringTranscodeProvider?.invoke() == true
		val shouldBurnIn = requestedSubStream?.deliveryMethod == SubtitleDeliveryMethod.ENCODE || isUnsupportedSubCodec || burnInPreference || isEmbeddedSub

		val startTicks = item.userData?.playbackPositionTicks ?: 0L

		val response = api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				startTimeTicks = startTicks,
				deviceProfile = profile,
				maxStreamingBitrate = maxBitrate.takeIf { it > 0 },
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
				audioStreamIndex = audioIndexToUse,
				subtitleStreamIndex = subIndexToUse,
				alwaysBurnInSubtitleWhenTranscoding = shouldBurnIn,
			)
		).content

		if (response.errorCode != null) {
			error("Failed to get media info for item ${item.id} source ${mediaSourceId}: ${response.errorCode}")
		}

		val mediaSource = response.mediaSources
			.firstOrNull { mediaSourceId != null && it.id == mediaSourceId }
			?: response.mediaSources.firstOrNull()

		requireNotNull(mediaSource) {
			"Failed to get media info for item ${item.id} source ${mediaSourceId}: media source missing in response"
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource
		)
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
	): PlayableMediaStream {
		val startTicks = queueEntry.baseItem?.userData?.playbackPositionTicks ?: 0L
		val currentBaseItem = queueEntry.baseItem
		if (currentBaseItem != null) {
			val existingSources = currentBaseItem.mediaSources.orEmpty()
			val updatedSources = if (existingSources.none { it.id == mediaSource.id }) {
				listOf(mediaSource) + existingSources
			} else existingSources
			val updatedMediaStreams = currentBaseItem.mediaStreams?.takeIf { it.isNotEmpty() }
				?: mediaSource.mediaStreams
			val clearedUserData = currentBaseItem.userData?.copy(playbackPositionTicks = 0L)
			queueEntry.baseItem = currentBaseItem.copy(
				mediaSources = updatedSources,
				mediaStreams = updatedMediaStreams,
				userData = clearedUserData
			)
		}
		return PlayableMediaStream(
			identifier = playSessionId,
			conversionMethod = conversionMethod,
			container = getMediaStreamContainer(),
			tracks = getTracks(api),
			queueEntry = queueEntry,
			url = url,
			startPositionTicks = startTicks,
			mediaSourceId = mediaSource.id,
		)
	}

}

private fun String.withStreamIndexOverrides(audioIndex: Int?, subtitleIndex: Int?, videoIndex: Int? = null): String {
	var result = this
	if (audioIndex != null && audioIndex >= 0) {
		result = if (result.contains("AudioStreamIndex=")) {
			result.replace(Regex("AudioStreamIndex=\\d+"), "AudioStreamIndex=$audioIndex")
		} else {
			"$result&AudioStreamIndex=$audioIndex"
		}
	}
	if (subtitleIndex != null) {
		result = if (result.contains("SubtitleStreamIndex=")) {
			result.replace(Regex("SubtitleStreamIndex=-?\\d+"), "SubtitleStreamIndex=$subtitleIndex")
		} else {
			"$result&SubtitleStreamIndex=$subtitleIndex"
		}
	}
	if (videoIndex != null && videoIndex >= 0) {
		result = if (result.contains("VideoStreamIndex=")) {
			result.replace(Regex("VideoStreamIndex=\\d+"), "VideoStreamIndex=$videoIndex")
		} else {
			"$result&VideoStreamIndex=$videoIndex"
		}
	}
	return result
}


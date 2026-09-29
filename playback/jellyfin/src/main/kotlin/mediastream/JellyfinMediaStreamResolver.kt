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

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
	private val localItemInspector: (suspend (File) -> BaseItemDto)? = null,
	private val streamIndexResolver: ((BaseItemDto, MediaSourceInfo?) -> Pair<Int?, Int?>)? = null,
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

			val itemToUse = if (baseItem.mediaStreams.isNullOrEmpty() || baseItem.chapters == null || baseItem.runTimeTicks == null) {
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
			streamIndexResolver?.invoke(itemToUse, initialSource)

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
			getPlaybackInfo(baseItem, queueEntry.mediaSourceId)
		}.getOrNull() ?: return null

		return when {
			// Direct play video
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.VIDEO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.videosApi.getVideoStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

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
			mediaInfo.mediaSource.supportsDirectStream && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Remux,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// Transcode
			mediaInfo.mediaSource.supportsTranscoding && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// No compatible stream found
			else -> null
		}
	}

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String? = null,
	): MediaInfo {
		val initialSource = item.mediaSources?.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }
			?: item.mediaSources?.firstOrNull()

		val (bestAudioIndex, bestSubIndex) = streamIndexResolver?.invoke(item, initialSource) ?: Pair(null, null)
		val startTicks = item.userData?.playbackPositionTicks ?: 0L

		val profile = deviceProfileBuilder()
		val response = api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				startTimeTicks = startTicks,
				deviceProfile = profile,
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
				audioStreamIndex = bestAudioIndex,
				subtitleStreamIndex = bestSubIndex,
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
			if (existingSources.none { it.id == mediaSource.id }) {
				queueEntry.baseItem = currentBaseItem.copy(mediaSources = listOf(mediaSource) + existingSources)
			}
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


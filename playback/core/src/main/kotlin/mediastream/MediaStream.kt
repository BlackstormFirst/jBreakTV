package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.queue.QueueEntry

interface MediaStream {
	val identifier: String
	val conversionMethod: MediaConversionMethod
	val container: MediaStreamContainer
	val tracks: Collection<MediaStreamTrack>
}

data class BasicMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
) : MediaStream {
	fun toPlayableMediaStream(
		queueEntry: QueueEntry,
		url: String,
		startPositionTicks: Long = 0L,
		mediaSourceId: String? = null,
	) = PlayableMediaStream(
		identifier = identifier,
		conversionMethod = conversionMethod,
		container = container,
		tracks = tracks,
		queueEntry = queueEntry,
		url = url,
		startPositionTicks = startPositionTicks,
		mediaSourceId = mediaSourceId,
	)
}

data class PlayableMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
	val queueEntry: QueueEntry,
	val url: String,
	val startPositionTicks: Long = 0L,
	val mediaSourceId: String? = null,
) : MediaStream

data class MediaStreamContainer(
	val format: String,
)

sealed interface MediaStreamTrack {
	val codec: String
}

data class MediaStreamAudioTrack(
	override val codec: String,
	val index: Int = -1,
	val language: String? = null,
	val title: String? = null,
	val displayTitle: String? = null,
	val bitrate: Int = 0,
	val channels: Int = 1,
	val sampleRate: Int = 0,
	val isDefault: Boolean = false,
) : MediaStreamTrack

data class MediaStreamVideoTrack(
	override val codec: String,
	val index: Int = -1,
	val bitrate: Int,
	val width: Int,
	val height: Int,
	val videoRange: String?,
) : MediaStreamTrack

data class MediaStreamSubtitleTrack(
	override val codec: String,
	val index: Int,
	val language: String?,
	val title: String?,
	val displayTitle: String?,
	val isExternal: Boolean,
	val isDefault: Boolean,
	val isForced: Boolean,
	val deliveryUrl: String?,
) : MediaStreamTrack

package org.jellyfin.playback.jellyfin.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.preferredAudioIndex
import org.jellyfin.playback.core.queue.preferredSubtitleIndex
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.util.isLocalPath
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QueueItem
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.sdk.model.api.RepeatMode as SdkRepeatMode

class PlaySessionService(
	private val api: ApiClient,
	private val onPlaybackStop: ((BaseItemDto) -> Unit)? = null,
) : PlayerService() {
	private data class ActiveSessionInfo(
		val itemId: UUID,
		val mediaSourceId: String?,
		val playSessionId: String,
		val playlistItemId: String?,
		val itemName: String?,
		val isLocal: Boolean,
	)

	private var isSessionStarted = false
	private var activeSession: ActiveSessionInfo? = null
	private var lastActivePositionMs: Long = 0L
	private var activeEntry: QueueEntry? = null
	private var activeItem: BaseItemDto? = null
	private var hasLastPlaybackFailed = false
	private var activeUpdateJob: Job? = null

	private fun isLocalMedia(item: BaseItemDto): Boolean {
		return isLocalPath(item.path)
	}

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			if (entry != null) {
				activeEntry = entry
				entry.baseItem?.let { activeItem = it }
			}
		}.launchIn(coroutineScope)

		state.playState.onEach { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					hasLastPlaybackFailed = false
					if (!isSessionStarted) {
						sendStreamStart()
					} else {
						sendStreamUpdate()
					}
				}
				PlayState.PAUSED -> sendStreamUpdate()
				PlayState.ERROR -> {
					hasLastPlaybackFailed = true
					if (!isSessionStarted) {
						sendStreamStop(failed = true)
					}
				}
				PlayState.STOPPED -> {
					sendStreamStop(failed = hasLastPlaybackFailed)
					hasLastPlaybackFailed = false
				}
			}
		}.launchIn(coroutineScope)

		coroutineScope.launch {
			var pauseTimerMs = 0L
			while (isActive) {
				delay(3.seconds)
				when (state.playState.value) {
					PlayState.PLAYING -> {
						pauseTimerMs = 0L
						sendStreamUpdate()
					}
					PlayState.PAUSED -> {
						pauseTimerMs += 3000L
						if (pauseTimerMs >= 15000L) {
							pauseTimerMs = 0L
							sendStreamUpdate()
						}
					}
					else -> {
						pauseTimerMs = 0L
					}
				}
			}
		}
	}

	private val MediaConversionMethod.playMethod
		get() = when (this) {
			MediaConversionMethod.None -> PlayMethod.DIRECT_PLAY
			MediaConversionMethod.Remux -> PlayMethod.DIRECT_STREAM
			MediaConversionMethod.Transcode -> PlayMethod.TRANSCODE
		}

	private val RepeatMode.remoteRepeatMode
		get() = when (this) {
			RepeatMode.NONE -> SdkRepeatMode.REPEAT_NONE
			RepeatMode.REPEAT_ENTRY_ONCE -> SdkRepeatMode.REPEAT_ONE
			RepeatMode.REPEAT_ENTRY_INFINITE -> SdkRepeatMode.REPEAT_ALL
		}

	suspend fun sendUpdateIfActive() {
		if (state.playState.value != PlayState.STOPPED) {
			coroutineScope.launch { sendStreamUpdate() }
		}
	}

	private suspend fun getQueue(): List<QueueItem> {
		return manager.queue
			.peekNext(15)
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id, playlistItemId = it.playlistItemId) }
	}

	private suspend fun getReportPositionMs(playableStream: PlayableMediaStream?): Long {
		val activeMs = withContext(Dispatchers.Main) {
			runCatching { state.positionInfo.active.inWholeMilliseconds }.getOrDefault(0L)
		}
		if (activeMs > 0) {
			lastActivePositionMs = activeMs
			return activeMs
		}
		if (lastActivePositionMs > 0) {
			return lastActivePositionMs
		}
		val startPosMs = (playableStream?.startPositionTicks ?: 0L) / 10000L
		if (startPosMs > 0) {
			lastActivePositionMs = startPosMs
			return startPosMs
		}
		return 0L
	}

	private suspend fun sendStreamStart() {
		val entry = manager.queue.entry.value ?: activeEntry ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: activeItem ?: return
		if (isLocalMedia(item)) return

		val playableStream = stream
		val mediaSourceId = playableStream.mediaSourceId
			?: item.mediaSources?.firstOrNull { it.id == entry.mediaSourceId }?.id
			?: item.mediaSources?.firstOrNull()?.id
			?: entry.mediaSourceId

		val reportMs = getReportPositionMs(playableStream)

		val sessionInfo = ActiveSessionInfo(
			itemId = item.id,
			mediaSourceId = mediaSourceId,
			playSessionId = stream.identifier,
			playlistItemId = item.playlistItemId,
			itemName = item.name,
			isLocal = false,
		)
		activeSession = sessionInfo

		withContext(Dispatchers.IO) {
			runCatching {
				api.playStateApi.reportPlaybackStart(
					PlaybackStartInfo(
						itemId = item.id,
						mediaSourceId = mediaSourceId,
						playSessionId = stream.identifier,
						playlistItemId = item.playlistItemId,
						canSeek = true,
						isMuted = state.volume.muted,
						volumeLevel = (state.volume.volume * 100).roundToInt(),
						isPaused = state.playState.value != PlayState.PLAYING,
						aspectRatio = state.videoSize.value.aspectRatio.toString(),
						positionTicks = reportMs * 10000L,
						playMethod = stream.conversionMethod.playMethod,
						repeatMode = state.repeatMode.value.remoteRepeatMode,
						nowPlayingQueue = getQueue(),
						playbackOrder = when (state.playbackOrder.value) {
							org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
							org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
							org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
						},
						audioStreamIndex = entry.preferredAudioIndex,
						subtitleStreamIndex = entry.preferredSubtitleIndex,
					)
				)
				isSessionStarted = true
				Timber.i("Reported playback start for ${item.name} at reportMs=$reportMs (mediaSourceId=$mediaSourceId, playSessionId=${stream.identifier})")
			}.onFailure { error -> Timber.w(error, "Failed to send playback start event") }
		}
	}

	private suspend fun sendStreamUpdate() {
		if (state.playState.value == PlayState.STOPPED) return
		val entry = manager.queue.entry.value ?: activeEntry ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: activeItem ?: return
		if (isLocalMedia(item)) return

		if (!isSessionStarted) {
			sendStreamStart()
			if (!isSessionStarted) return
		}

		val playableStream = stream
		val mediaSourceId = playableStream.mediaSourceId
			?: item.mediaSources?.firstOrNull { it.id == entry.mediaSourceId }?.id
			?: item.mediaSources?.firstOrNull()?.id
			?: entry.mediaSourceId

		val reportMs = getReportPositionMs(playableStream)

		val sessionInfo = ActiveSessionInfo(
			itemId = item.id,
			mediaSourceId = mediaSourceId,
			playSessionId = stream.identifier,
			playlistItemId = item.playlistItemId,
			itemName = item.name,
			isLocal = false,
		)
		activeSession = sessionInfo

		val updateJob = coroutineScope.launch(Dispatchers.IO) {
			runCatching {
				api.playStateApi.reportPlaybackProgress(
					PlaybackProgressInfo(
						itemId = item.id,
						mediaSourceId = mediaSourceId,
						playSessionId = stream.identifier,
						playlistItemId = item.playlistItemId,
						canSeek = true,
						isMuted = state.volume.muted,
						volumeLevel = (state.volume.volume * 100).roundToInt(),
						isPaused = state.playState.value != PlayState.PLAYING,
						aspectRatio = state.videoSize.value.aspectRatio.toString(),
						positionTicks = reportMs * 10000L,
						playMethod = stream.conversionMethod.playMethod,
						repeatMode = state.repeatMode.value.remoteRepeatMode,
						nowPlayingQueue = getQueue(),
						playbackOrder = when (state.playbackOrder.value) {
							org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
							org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
							org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
						},
						audioStreamIndex = entry.preferredAudioIndex,
						subtitleStreamIndex = entry.preferredSubtitleIndex,
					)
				)
				Timber.d("Reported playback progress for ${item.name} at reportMs=$reportMs")
			}.onFailure { error -> Timber.w(error, "Failed to send playback update event") }
		}
		activeUpdateJob = updateJob
	}

	private suspend fun sendStreamStop(failed: Boolean = false) {
		val entry = manager.queue.entry.value ?: activeEntry
		val item = entry?.baseItem ?: activeItem
		if (item != null) {
			onPlaybackStop?.invoke(item)
		}

		// Cancel any pending progress update job so a late progress report doesn't override the stop report
		runCatching { activeUpdateJob?.cancel() }
		activeUpdateJob = null

		// Retrieve session info from cache or active entry/stream
		val sessionInfo = activeSession ?: run {
			val stream = entry?.mediaStream ?: activeEntry?.mediaStream ?: return@run null
			val currentItem = item ?: return@run null
			if (isLocalMedia(currentItem)) return@run null
			ActiveSessionInfo(
				itemId = currentItem.id,
				mediaSourceId = stream.mediaSourceId ?: currentItem.mediaSources?.firstOrNull()?.id,
				playSessionId = stream.identifier,
				playlistItemId = currentItem.playlistItemId,
				itemName = currentItem.name,
				isLocal = false,
			)
		}

		if (sessionInfo == null || sessionInfo.isLocal) {
			isSessionStarted = false
			activeSession = null
			activeEntry = null
			activeItem = null
			lastActivePositionMs = 0L
			return
		}

		// Try to fetch latest live position before backend is stopped, fallback to last known
		val livePositionMs = withContext(Dispatchers.Main) {
			runCatching { state.positionInfo.active.inWholeMilliseconds }.getOrDefault(0L)
		}
		val reportMs = if (livePositionMs > 0) livePositionMs else lastActivePositionMs

		withContext(Dispatchers.IO + NonCancellable) {
			runCatching {
				api.playStateApi.reportPlaybackStopped(
					PlaybackStopInfo(
						itemId = sessionInfo.itemId,
						mediaSourceId = sessionInfo.mediaSourceId,
						playSessionId = sessionInfo.playSessionId,
						playlistItemId = sessionInfo.playlistItemId,
						positionTicks = reportMs * 10000L,
						failed = failed,
						nowPlayingQueue = getQueue(),
					)
				)
				Timber.i("Reported playback stop for ${sessionInfo.itemName} at reportMs=$reportMs (playSessionId=${sessionInfo.playSessionId})")
			}.onFailure { error -> Timber.w(error, "Failed to send playback stop event") }

			isSessionStarted = false
			activeSession = null
			activeEntry = null
			activeItem = null
			lastActivePositionMs = 0L
		}
	}
}

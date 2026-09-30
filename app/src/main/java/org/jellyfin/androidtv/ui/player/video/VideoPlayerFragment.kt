package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackIndexManager
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.RefreshRateHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoSize
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.koin.android.ext.android.inject
import timber.log.Timber

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()
	private val navigationRepository by inject<NavigationRepository>()

	private var refreshRateHelper: RefreshRateHelper? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		refreshRateHelper = RefreshRateHelper(requireActivity(), userPreferences)

		val items = videoQueueManager.getCurrentVideoQueue().toMutableList()
		val currentMediaPosition = videoQueueManager.getCurrentMediaPosition()

		if (savedInstanceState == null && currentMediaPosition in items.indices) {
			// Clear playbackPositionTicks for all queue items EXCEPT the initial currentMediaPosition
			items.indices.forEach { index ->
				if (index != currentMediaPosition) {
					val item = items[index]
					val clearedUserData = item.userData?.copy(playbackPositionTicks = 0L)
					if (clearedUserData != null) {
						items[index] = item.copy(userData = clearedUserData)
					}
				}
			}

			val targetItem = items[currentMediaPosition]
			if (arguments?.containsKey(EXTRA_POSITION) == true) {
				val startPositionMs = arguments?.getInt(EXTRA_POSITION, 0) ?: 0
				val startPositionTicks = startPositionMs.toLong() * 10000L
				val updatedUserData = targetItem.userData?.copy(playbackPositionTicks = startPositionTicks)
					?: UserItemDataDto(
						itemId = targetItem.id,
						playbackPositionTicks = startPositionTicks,
						playCount = 0,
						isFavorite = false,
						played = false,
						key = targetItem.id.toString(),
					)
				items[currentMediaPosition] = targetItem.copy(userData = updatedUserData)
			} else {
				val serverTicks = targetItem.userData?.playbackPositionTicks ?: 0L
				if (serverTicks > 0L) {
					val resumeSubtractSeconds = userPreferences[UserPreferences.resumeSubtractDuration].toLongOrNull() ?: 0L
					val adjustedTicks = (serverTicks - (resumeSubtractSeconds * 10000000L)).coerceAtLeast(0L)
					val updatedUserData = targetItem.userData?.copy(playbackPositionTicks = adjustedTicks)
					if (updatedUserData != null) {
						items[currentMediaPosition] = targetItem.copy(userData = updatedUserData)
					}
				}
			}
			videoQueueManager.setCurrentVideoQueue(items)
		}

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, items, false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier, currentMediaPosition)

		// Sync current media position with VideoQueueManager as playback advances in the queue
		lifecycleScope.launch {
			playbackManager.queue.entryIndex.collect { index ->
				if (index >= 0) {
					videoQueueManager.setCurrentMediaPosition(index)
				}
			}
		}

		// Observe video size and queue entry to apply refresh rate switching
		lifecycleScope.launch {
			playbackManager.state.videoSize.collect { videoSize ->
				applyRefreshRate(videoSize)
			}
		}

		// Observe play state to navigate back when playback is stopped after starting
		lifecycleScope.launch {
			var hasStarted = false
			playbackManager.state.playState.collect { playState ->
				when (playState) {
					PlayState.PLAYING, PlayState.PAUSED -> {
						hasStarted = true
					}
					PlayState.STOPPED -> {
						if (hasStarted && isAdded && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
							navigationRepository.goBack()
						}
					}
					else -> Unit
				}
			}
		}

		// Listen for track changes to apply best video/audio/subtitle selections
		playbackManager.addListener(object : PlayerBackendEventListener() {
			private var isApplyingAutoTracks = false
			private var lastAutoSelectedEntryId: String? = null

			override fun onTracksChanged() {
				if (isApplyingAutoTracks) return

				val entry = playbackManager.queue.entry.value ?: return
				val baseItem = entry.baseItem ?: return
				val entryId = baseItem.id.toString()
				if (lastAutoSelectedEntryId == entryId) {
					// Initial auto-selection for this media item was already applied.
					// Do not override user's manual track selection!
					return
				}

				isApplyingAutoTracks = true
				try {
					val baseItem = entry.baseItem ?: return
					val backend = playbackManager.backend

					val videoTracks = backend.getTracks(2)
					val audioTracks = backend.getTracks(1)
					val subTracks = backend.getTracks(3)

					if (videoTracks.isEmpty() && audioTracks.isEmpty() && subTracks.isEmpty()) {
						// Tracks not loaded yet in ExoPlayer, retry on next onTracksChanged
						return
					}

					val mediaSource = baseItem.mediaSources?.firstOrNull() ?: MediaSourceInfo(
						protocol = MediaProtocol.FILE,
						id = baseItem.id.toString(),
						path = baseItem.path,
						type = MediaSourceType.DEFAULT,
						isRemote = false,
						mediaStreams = baseItem.mediaStreams,
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

					val playbackIndexManager: PlaybackIndexManager by inject()
					val audioIndex = playbackIndexManager.getBestAudioIndex(mediaSource)
					val activeAudioLang = baseItem.mediaStreams?.firstOrNull { it.index == audioIndex }?.language
					val subIndex = playbackIndexManager.getBestSubtitleIndex(mediaSource, context, activeAudioLang)
					val videoIndex = playbackIndexManager.getBestVideoIndex(mediaSource)

					// Auto-select video track if needed
					if (videoIndex != null) {
						val targetTrack = videoTracks.firstOrNull { it.mediaStreamIndex == videoIndex }
							?: videoTracks.firstOrNull()
						if (targetTrack != null && !targetTrack.isSelected) {
							backend.selectTrack(2, targetTrack)
						}
					}

					// Auto-select audio track if needed
					if (audioIndex != null) {
						val targetTrack = audioTracks.firstOrNull { it.mediaStreamIndex == audioIndex }
							?: audioTracks.firstOrNull { it.language.equals(activeAudioLang, ignoreCase = true) }
						if (targetTrack != null && !targetTrack.isSelected) {
							backend.selectTrack(1, targetTrack)
						}
					}

					// Auto-select subtitle track if needed
					if (subIndex == null || subIndex < 0) {
						if (subTracks.any { it.isSelected }) {
							backend.selectTrack(3, null)
						}
					} else {
						val targetStream = baseItem.mediaStreams?.firstOrNull { it.index == subIndex }
						val targetTrack = subTracks.firstOrNull { it.mediaStreamIndex == subIndex }
							?: subTracks.firstOrNull { it.language.equals(targetStream?.language, ignoreCase = true) }
						if (targetTrack != null && !targetTrack.isSelected) {
							backend.selectTrack(3, targetTrack)
						}
					}

					val hasSubtitleStreams = baseItem.mediaStreams?.any { it.type == MediaStreamType.SUBTITLE } == true
					if (!hasSubtitleStreams || subTracks.isNotEmpty()) {
						lastAutoSelectedEntryId = entryId
					}
				} finally {
					isApplyingAutoTracks = false
				}
			}
		})

		// Pause player until the initial resume
		playbackManager.state.pause()
	}

	private fun applyRefreshRate(videoSize: VideoSize) {
		val entry = playbackManager.queue.entry.value
		val baseItem = entry?.baseItem
		val mediaStreamFrameRate = baseItem?.mediaStreams
			?.firstOrNull { it.type == MediaStreamType.VIDEO }
			?.realFrameRate

		val frameRate = when {
			videoSize.frameRate > 0f -> videoSize.frameRate
			mediaStreamFrameRate != null && mediaStreamFrameRate > 0f -> mediaStreamFrameRate
			else -> return
		}

		refreshRateHelper?.updateRefreshRate(
			frameRate = frameRate,
			videoWidth = videoSize.width,
			videoHeight = videoSize.height,
		)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		BaseScreen {
			VideoPlayerScreen()
		}
	}

	override fun onPause() {
		super.onPause()

		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		if (playbackManager.state.playState.value == PlayState.STOPPED) {
			playbackManager.state.play()
		} else {
			playbackManager.state.unpause()
		}
	}


	override fun onStop() {
		super.onStop()

		refreshRateHelper?.resetRefreshRate()
		playbackManager.state.stop()
	}

	override fun onDestroy() {
		super.onDestroy()

		videoQueueManager.clearVideoQueue()
	}
}

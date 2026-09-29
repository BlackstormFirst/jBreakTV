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
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.RefreshRateHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.VideoSize
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
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
}

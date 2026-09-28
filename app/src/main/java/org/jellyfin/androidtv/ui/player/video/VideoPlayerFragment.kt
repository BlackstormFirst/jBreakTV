package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.RefreshRateHelper
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.VideoSize
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val userPreferences by inject<UserPreferences>()
	private val api by inject<ApiClient>()

	private var refreshRateHelper: RefreshRateHelper? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		refreshRateHelper = RefreshRateHelper(requireActivity(), userPreferences)

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, videoQueueManager.getCurrentVideoQueue(), false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier)

		// Set position
		arguments?.getInt(EXTRA_POSITION)?.milliseconds?.let {
			lifecycleScope.launch {
				playbackManager.state.seek(it)
			}
		}

		// Observe video size and queue entry to apply refresh rate switching
		lifecycleScope.launch {
			playbackManager.state.videoSize.collect { videoSize ->
				applyRefreshRate(videoSize)
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

		playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		refreshRateHelper?.resetRefreshRate()
		playbackManager.state.stop()
	}
}

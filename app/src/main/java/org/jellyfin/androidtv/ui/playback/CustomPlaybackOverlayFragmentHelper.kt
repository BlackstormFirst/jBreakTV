package org.jellyfin.androidtv.ui.playback

import android.view.View
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.asTimerInfoDto
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

fun CustomPlaybackOverlayFragment.toggleFavorite() {
	val header = mSelectedProgramView as? GuideChannelHeader
	val channel = header?.channel ?: return

	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = header.channel.id,
				favorite = !(channel.userData?.isFavorite ?: false)
			)

			header.channel = header.channel.copy(userData = userData)
			header.findViewById<View>(R.id.favImage).isVisible = userData.isFavorite
			dataRefreshService.lastFavoriteUpdate = Instant.now()
		}
	}
}

fun CustomPlaybackOverlayFragment.refreshSelectedProgram() {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			val item = withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(mSelectedProgram.id).content
			}
			mSelectedProgram = item
		}.onFailure { error ->
			Timber.e(error, "Unable to get program details")
		}

		detailUpdateInternal();
	}
}

fun CustomPlaybackOverlayFragment.playChannel(id: UUID) {
	val api by inject<ApiClient>()
	val playbackControllerContainer by inject<PlaybackControllerContainer>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(id).content
			}
		}.fold(
			onSuccess = { channel ->
				playbackControllerContainer.playbackController?.setItems(listOf(channel))
				playbackControllerContainer.playbackController?.play(0)
			},
			onFailure = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_video_playback_error),
					Toast.LENGTH_LONG
				).show()

				closePlayer()
			},
		)
	}
}

fun CustomPlaybackOverlayFragment.cancelTimer(id: String) {
	val api by inject<ApiClient>()
	val playbackControllerContainer by inject<PlaybackControllerContainer>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelTimer(id)
			}
		}.fold(
			onSuccess = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_recording_cancelled),
					Toast.LENGTH_LONG
				).show()
				playbackControllerContainer.playbackController?.updateTvProgramInfo()
				TvManager.forceReload()
			},
			onFailure = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_unable_to_cancel),
					Toast.LENGTH_LONG
				).show()

				closePlayer()
			},
		)
	}
}

fun CustomPlaybackOverlayFragment.cancelSeriesTimer(id: String) {
	val api by inject<ApiClient>()
	val playbackControllerContainer by inject<PlaybackControllerContainer>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelSeriesTimer(id)
			}
		}.fold(
			onSuccess = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_recording_cancelled),
					Toast.LENGTH_LONG
				).show()
				playbackControllerContainer.playbackController?.updateTvProgramInfo()
				TvManager.forceReload()
			},
			onFailure = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_unable_to_cancel),
					Toast.LENGTH_LONG
				).show()

				closePlayer()
			},
		)
	}
}

fun CustomPlaybackOverlayFragment.recordProgram(program: BaseItemDto, isSeries: Boolean) {
	val api by inject<ApiClient>()
	val playbackControllerContainer by inject<PlaybackControllerContainer>()

	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val defaultTimer by api.liveTvApi.getDefaultTimer(program.id.toString())

				if (isSeries) {
					api.liveTvApi.createSeriesTimer(defaultTimer)
				} else {
					api.liveTvApi.createTimer(defaultTimer.asTimerInfoDto())
				}
			}
		}.fold(
			onSuccess = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_set_to_record),
					Toast.LENGTH_LONG
				).show()
				playbackControllerContainer.playbackController?.updateTvProgramInfo()
				TvManager.forceReload()
			},
			onFailure = {
				Toast.makeText(
					requireContext(),
					getString(R.string.msg_unable_to_create_recording),
					Toast.LENGTH_LONG
				).show()
			}
		)
	}
}

fun CustomPlaybackOverlayFragment.askToSkip(position: Duration) {
	val b = binding ?: return
	b.skipOverlay.targetPosition = position
}

fun CustomPlaybackOverlayFragment.setupToastOverlay() {
	val b = binding ?: return
	val registry = MediaToastRegistry(viewLifecycleOwner.lifecycleScope)
	b.toastOverlay.setContent {
		JellyfinTheme {
			MediaToasts(registry)
		}
	}
	b.toastOverlay.setTag(R.id.toast_overlay, registry)
}

fun CustomPlaybackOverlayFragment.resetPlayStateToast() {
	val b = binding ?: return
	val stateKey = R.id.toast_overlay + 1
	b.toastOverlay.setTag(stateKey, null)
}

fun CustomPlaybackOverlayFragment.onPlayStateChanged(isPlaying: Boolean) {
	val b = binding ?: return
	val registry = b.toastOverlay.getTag(R.id.toast_overlay) as? MediaToastRegistry ?: return
	val stateKey = R.id.toast_overlay + 1
	val previousState = b.toastOverlay.getTag(stateKey) as? String

	if (previousState == null) {
		if (isPlaying) {
			b.toastOverlay.setTag(stateKey, "PLAYING")
		}
		return
	}

	if (previousState == "PLAYING" && !isPlaying) {
		b.toastOverlay.setTag(stateKey, "PAUSED")
		registry.emit(R.drawable.ic_pause)
	} else if (previousState == "PAUSED" && isPlaying) {
		b.toastOverlay.setTag(stateKey, "PLAYING")
		registry.emit(R.drawable.ic_play)
	}
}

fun CustomPlaybackOverlayFragment.setupBufferingOverlay() {
	val b = binding ?: return
	val bufferingKey = R.id.buffering_overlay
	val stateFlow = MutableStateFlow(true)
	b.bufferingOverlay.setTag(bufferingKey, stateFlow)

	b.bufferingOverlay.setContent {
		val isBuffering by stateFlow.collectAsState()
		if (isBuffering) {
			Box(
				modifier = Modifier.fillMaxSize(),
				contentAlignment = Alignment.Center,
			) {
				Box(
					modifier = Modifier
						.size(72.dp)
						.background(
							color = Color.Black.copy(alpha = 0.65f),
							shape = RoundedCornerShape(36.dp)
						)
						.padding(16.dp),
					contentAlignment = Alignment.Center
				) {
					CircularProgressIndicator(
						modifier = Modifier.fillMaxSize(),
						color = Color.White
					)
				}
			}
		}
	}
}

fun CustomPlaybackOverlayFragment.showBuffering(show: Boolean) {
	val b = binding ?: return
	val bufferingKey = R.id.buffering_overlay
	@Suppress("UNCHECKED_CAST")
	val stateFlow = b.bufferingOverlay.getTag(bufferingKey) as? MutableStateFlow<Boolean>
	stateFlow?.value = show
}


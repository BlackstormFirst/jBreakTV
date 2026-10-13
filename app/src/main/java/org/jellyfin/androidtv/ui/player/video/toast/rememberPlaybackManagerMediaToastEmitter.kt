package org.jellyfin.androidtv.ui.player.video.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState

@Composable
fun rememberPlaybackManagerMediaToastEmitter(
	playbackManager: PlaybackManager,
	mediaToastRegistry: MediaToastRegistry,
) {
	val coroutineScope = rememberCoroutineScope()

	LaunchedEffect(playbackManager) {
		var active = false
		var wasBuffering = false
		var previousState: PlayState? = null

		// Track buffering state
		playbackManager.state.isBuffering
			.onEach { buffering ->
				if (buffering) {
					wasBuffering = true
				}
			}
			.launchIn(coroutineScope)

		// Track play state
		playbackManager.state.playState
			.onEach { currentState ->
				val lastState = previousState
				previousState = currentState

				if (!active) {
					if (currentState == PlayState.PLAYING) {
						active = true
						wasBuffering = false
					}
					return@onEach
				}

				when (currentState) {
					PlayState.PLAYING -> {
						if (wasBuffering && (lastState != PlayState.PAUSED)) {
							// Ignore showing play toast if resuming from buffering
							wasBuffering = false
						} else {
							wasBuffering = false
							mediaToastRegistry.emit(R.drawable.ic_play)
						}
					}
					PlayState.PAUSED -> {
						wasBuffering = false
						mediaToastRegistry.emit(R.drawable.ic_pause)
					}
					else -> Unit
				}
			}
			.launchIn(coroutineScope)
	}
}

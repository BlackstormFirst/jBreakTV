package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideScreen
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.util.UUID

@Composable
fun LiveTvGuideOverlay(
	visible: Boolean,
	onDismissRequest: () -> Unit,
	onChannelSelected: (UUID) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (!visible) return

	val coroutineScope = rememberCoroutineScope()
	val viewModel = koinViewModel<LiveTvGuideViewModel>()
	val imageHelper = koinInject<ImageHelper>()

	Box(
		modifier = modifier
			.fillMaxSize()
			.background(Color.Black.copy(alpha = 0.85f))
			.padding(vertical = 24.dp, horizontal = 16.dp)
			.onKeyEvent { keyEvent ->
				if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
				if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
					onDismissRequest()
					true
				} else {
					false
				}
			}
	) {
		LiveTvGuideScreen(
			viewModel = viewModel,
			getImageUrl = { channel ->
				imageHelper.getPrimaryImageUrl(channel, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
			},
			onProgramSelected = { /* Do nothing for focus in overlay */ },
			onProgramClicked = { program ->
				program.channelId?.let { channelId ->
					coroutineScope.launch {
						onChannelSelected(channelId)
						onDismissRequest()
					}
				}
			},
			onOptionsClicked = { /* No options in overlay */ },
			onFiltersClicked = { /* No filters in overlay */ }
		)
	}
}

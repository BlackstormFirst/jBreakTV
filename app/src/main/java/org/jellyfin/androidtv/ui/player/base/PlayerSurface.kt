package org.jellyfin.androidtv.ui.player.base

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.koin.compose.koinInject

@Composable
fun PlayerSurface(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
) {
	AndroidView(
		factory = { context ->
			PlayerSurfaceView(context).apply {
				isFocusable = false
				isClickable = false
				importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
			}
		},
		modifier = modifier.focusProperties { canFocus = false },
		update = { view ->
			view.playbackManager = playbackManager
		}
	)
}

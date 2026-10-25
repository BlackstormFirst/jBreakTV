package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextClock
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.ClockBehavior
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ClockUserView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr), KoinComponent {

	private val userPreferences by inject<UserPreferences>()
	private val navigationRepository by inject<NavigationRepository>()

	var isVideoPlayer = false
		set(value) {
			field = value
			updateClockVisibility()
		}

	private var showClock by mutableStateOf(false)
	private var showHomeButton by mutableStateOf(false)

	init {
		updateClockVisibility()
	}

	private fun updateClockVisibility() {
		val behavior = userPreferences[UserPreferences.clockBehavior]

		showClock = when (behavior) {
			ClockBehavior.ALWAYS -> true
			ClockBehavior.NEVER -> false
			ClockBehavior.IN_VIDEO -> isVideoPlayer
			ClockBehavior.IN_MENUS -> !isVideoPlayer
		}

		showHomeButton = !isVideoPlayer
	}

	@Composable
	override fun Content() {
		JellyfinTheme {
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier.padding(8.dp)
			) {
				if (showHomeButton) {
					IconButton(
						onClick = {
							navigationRepository.reset(Destinations.home, clearHistory = true)
						}
					) {
						Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
					}
				}

				if (showClock && showHomeButton) {
					Spacer(modifier = Modifier.width(16.dp))
				}

				if (showClock) {
					AndroidView(
						factory = { ctx ->
							TextClock(ctx).apply {
								format12Hour = "h:mm"
								format24Hour = "k:mm"
								setTextColor(android.graphics.Color.WHITE)
								textSize = 20f
							}
						}
					)
				}
			}
		}
	}
}

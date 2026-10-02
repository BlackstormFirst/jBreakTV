package org.jellyfin.androidtv.ui.settings.screen.playback

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.nameRes
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.playback.core.LibassRenderMode
import org.koin.compose.koinInject

@Composable
fun SettingsPlaybackLibassRenderModeScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()
	var libassRenderMode by rememberPreference(userPreferences, UserPreferences.libassRenderMode)

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_subtitles).uppercase()) },
				headingContent = { Text(stringResource(R.string.preference_libass_render_mode)) },
			)
		}

		items(LibassRenderMode.entries) { entry ->
			ListButton(
				headingContent = { Text(stringResource(entry.nameRes)) },
				trailingContent = { RadioButton(checked = libassRenderMode == entry) },
				onClick = {
					libassRenderMode = entry
					router.back()
				},
				modifier = Modifier
					.focusKey("libass_render_mode_${entry.name}", initialFocus = libassRenderMode == entry)
			)
		}
	}
}

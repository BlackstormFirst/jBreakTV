package org.jellyfin.androidtv.ui.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.koin.compose.koinInject
import androidx.compose.runtime.mutableIntStateOf
import java.util.Locale
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import timber.log.Timber

@Composable
fun SettingsUserServerPrefsScreen(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val router = LocalRouter.current
	val apiClient = koinInject<ApiClient>()
	val userSettingPreferences = koinInject<UserSettingPreferences>()

	//var subtitleModeTitle by rememberPreference(userSettingPreferences, UserSettingPreferences.subMode)
	//var userLangPrefs by rememberPreference(userSettingPreferences, UserSettingPreferences.audioLangRemoteSetting)
	//var userSubPrefs by rememberPreference(userSettingPreferences, UserSettingPreferences.subLangRemoteSetting)
	//var userAlwaysUseAudioDefault by rememberPreference(userSettingPreferences, UserSettingPreferences.userAlwaysUseAudioDefault)

	var subtitleModeTitle by remember { mutableIntStateOf(userSettingPreferences[UserSettingPreferences.subMode]) }
	var userLangPrefsRaw by remember { mutableStateOf(userSettingPreferences[UserSettingPreferences.audioLangRemoteSetting])}
	var userSubPrefsRaw by remember { mutableStateOf(userSettingPreferences[UserSettingPreferences.subLangRemoteSetting])}
	var userAlwaysUseAudioDefault by remember { mutableStateOf(userSettingPreferences[UserSettingPreferences.userAlwaysUseAudioDefault])}

	/*
	LaunchedEffect(Unit) {
		runCatching {
			apiClient.userApi.getCurrentUser().content.configuration
		}.onSuccess { config ->
			subtitleModeTitle = when (config?.subtitleMode) {
				SubtitlePlaybackMode.DEFAULT -> R.string.subtitle_mode_default
				SubtitlePlaybackMode.ALWAYS -> R.string.subtitle_mode_always
				SubtitlePlaybackMode.ONLY_FORCED -> R.string.subtitle_mode_only_forced
				SubtitlePlaybackMode.SMART -> R.string.subtitle_mode_smart
				else -> R.string.subtitle_mode_none
			}
			userLangPrefs = config?.audioLanguagePreference
			userSubPrefs = config?.subtitleLanguagePreference
			userAlwaysUseAudioDefault = config?.playDefaultAudioTrack
		}
	}
	*/
	//Timber.i("Val lang: '%s'", userLangPrefs)
	//Timber.i("Val subs: '%s'", userSubPrefs)

	val anyLanguageText = stringResource(R.string.any_language)

	val userLangPrefs = remember(userLangPrefsRaw, anyLanguageText) {
		if (userLangPrefsRaw.isBlank()) {
			anyLanguageText
		} else {
			val displayLanguage = Locale.forLanguageTag(userLangPrefsRaw).getDisplayLanguage(Locale.getDefault())
			val finalDisplay = if (displayLanguage.isNotBlank() && displayLanguage != userLangPrefsRaw) {
				displayLanguage
			} else {
				runCatching { Locale.Builder().setLanguage(userLangPrefsRaw).build().getDisplayLanguage(Locale.getDefault()) }
					.getOrNull() ?: userLangPrefsRaw
			}
			finalDisplay.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
		}
	}

	val userSubPrefs = remember(userSubPrefsRaw, anyLanguageText) {
		if (userSubPrefsRaw.isBlank()) {
			anyLanguageText
		} else {
			val displayLanguage = Locale.forLanguageTag(userSubPrefsRaw).getDisplayLanguage(Locale.getDefault())
			val finalDisplay = if (displayLanguage.isNotBlank() && displayLanguage != userSubPrefsRaw) {
				displayLanguage
			} else {
				runCatching { Locale.Builder().setLanguage(userSubPrefsRaw).build().getDisplayLanguage(Locale.getDefault()) }
					.getOrNull() ?: userSubPrefsRaw
			}
			finalDisplay.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
		}
	}

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.profile_specific_settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.profile_specific_settings_from_server)) },
			)
		}

		item {
			//var audioLanguage by rememberPreference(userPreferences, UserPreferences.audioLanguageFromServer)
			ListButton(
				headingContent = { Text(stringResource(R.string.preferred_audio_language)) },
				captionContent = { Text(userLangPrefs) },
				onClick = { },
				modifier = Modifier.focusKey(""),
			)
		}

		item {
			ListButton(
				headingContent = { Text(stringResource(R.string.always_default_audio_stream)) },
				trailingContent = { Checkbox( checked = userAlwaysUseAudioDefault) },
				onClick = { },
				modifier = Modifier.focusKey(""),
				enabled = false
			)
		}

		item {
			//var subtitleLanguage by rememberPreference(userPreferences, UserPreferences.audioLanguageFromServer)
			ListButton(
				headingContent = { Text(stringResource(R.string.preferred_subtitle_language)) },
				captionContent = { Text(userSubPrefs) },
				onClick = { },
				modifier = Modifier.focusKey(""),
			)
		}

		item {
			//var subMode by rememberPreference(userPreferences, UserPreferences.audioLanguageFromServer)
			ListButton(
				headingContent = { Text(stringResource(R.string.subtitle_mode)) },
				captionContent = { Text(stringResource(subtitleModeTitle)) },
				onClick = { },
				modifier = Modifier.focusKey(""),
			)
		}
	}
}

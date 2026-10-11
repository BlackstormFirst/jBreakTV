package org.jellyfin.androidtv.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userApi
import timber.log.Timber

/**
 * Repository to access special preference stores.
 */
class PreferencesRepository(
	private val api: ApiClient,
	private val liveTvPreferences: LiveTvPreferences,
	private val userSettingPreferences: UserSettingPreferences,
) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val libraryPreferences = mutableMapOf<String, LibraryPreferences>()

	fun getLibraryPreferences(preferencesId: String): LibraryPreferences {
		val store = libraryPreferences[preferencesId] ?: LibraryPreferences(preferencesId, api)

		libraryPreferences[preferencesId] = store

		if (store.shouldUpdate) {
			scope.launch { store.update() }
		}

		return store
	}

	suspend fun getLibraryPreferencesAsync(preferencesId: String): LibraryPreferences = withContext(Dispatchers.IO) {
		val store = libraryPreferences[preferencesId] ?: LibraryPreferences(preferencesId, api)

		libraryPreferences[preferencesId] = store

		if (store.shouldUpdate) {
			store.update()
		}

		store
	}

	suspend fun refreshServerUserSettings() {
		Timber.i("Refresh Server User Settings.")
		val configuration = api.userApi.getCurrentUser().content.configuration
		userSettingPreferences.userServerRemoteSettings(configuration)
	}

	suspend fun onSessionChanged() {
		// Note: Do not run parallel as the server can't deal with that
		// Relevant server issue: https://github.com/jellyfin/jellyfin/issues/5261
		liveTvPreferences.update()
		userSettingPreferences.update()

		libraryPreferences.clear()
	}
}

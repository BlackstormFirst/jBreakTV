package org.jellyfin.androidtv.data.service

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.bitmapConfig
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val imageLoader: ImageLoader,
	private val serverRepository: ServerRepository,
) {
	companion object {
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds
		const val DEFAULT_DEBOUNCE_MS = 180L
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Tracking current and requested target IDs for centralized debounce
	@Volatile
	private var currentRequestedTargetId: String? = null
	@Volatile
	private var currentlyLoadedTargetId: String? = null

	// Current background data
	private var loadedUrls: Set<String> = emptySet()
	private var pendingUrls: Set<String>? = null
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	// Saved background for restoration after splash screen
	private var savedBackground: ImageBitmap? = null
	private var savedBackgrounds: List<ImageBitmap> = emptyList()
	private var savedLoadedUrls: Set<String> = emptySet()
	private var savedLoadedTargetId: String? = null

	/**
	 * Temporarily resets the background to the default theme background,
	 * while preserving the current background in memory to restore it after the splash screen.
	 */
	fun resetToThemeAndSaveExisting() {
		loadBackgroundsJob?.cancel()
		if (_currentBackground.value != null) {
			savedBackground = _currentBackground.value
			savedBackgrounds = _backgrounds
			savedLoadedUrls = loadedUrls
			savedLoadedTargetId = currentlyLoadedTargetId
		}
		_currentBackground.value = null
		currentlyLoadedTargetId = null
	}

	/**
	 * Restores the previously saved background after the splash screen completes.
	 */
	fun restoreSavedBackground() {
		val saved = savedBackground ?: return
		_backgrounds = savedBackgrounds
		loadedUrls = savedLoadedUrls
		currentlyLoadedTargetId = savedLoadedTargetId
		_currentBackground.value = saved
		savedBackground = null
		savedBackgrounds = emptyList()
		savedLoadedUrls = emptySet()
		savedLoadedTargetId = null
	}

	/**
	 * Use all available backdrops from [baseItem] as background.
	 */
	@JvmOverloads
	fun setBackground(
		baseItem: BaseItemDto?,
		debounceMs: Long = DEFAULT_DEBOUNCE_MS,
		fallbackToSplashscreen: Boolean = false,
	) {
		val backdropBehavior = userPreferences[UserPreferences.backdropBehavior]

		// Check if backgrounds are enabled
		if (backdropBehavior == BackdropBehavior.DISABLED) {
			return clearBackgrounds(debounceMs = debounceMs)
		}

		// Enable blur for backdrops
		_blurBackground.value = backdropBehavior == BackdropBehavior.BACKDROP_WITH_BLUR

		// Get all backdrop urls
		val backdropUrls = baseItem?.let { item ->
			(item.itemBackdropImages + item.parentBackdropImages)
				.map { it.getUrl(api) }
				.toSet()
		} ?: emptySet()

		if (backdropUrls.isNotEmpty()) {
			val itemId = baseItem?.id?.toString() ?: backdropUrls.first()
			requestBackgroundUpdate(
				targetId = itemId,
				backdropUrls = backdropUrls,
				debounceMs = debounceMs
			)
		} else if (fallbackToSplashscreen) {
			setSplashscreenBackground(debounceMs = debounceMs)
		} else {
			clearBackgrounds(debounceMs = debounceMs)
		}
	}

	@JvmOverloads
	fun setSplashscreenBackground(
		server: Server? = serverRepository.currentServer.value,
		debounceMs: Long = DEFAULT_DEBOUNCE_MS,
	) {
		if (server != null) {
			setBackground(server, debounceMs = debounceMs)
		} else {
			clearBackgrounds(debounceMs = debounceMs)
		}
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	@JvmOverloads
	fun setBackground(
		server: Server,
		debounceMs: Long = DEFAULT_DEBOUNCE_MS,
	) {
		// Check if item is set and backgrounds are enabled
		if (userPreferences[UserPreferences.backdropBehavior] == BackdropBehavior.DISABLED)
			return clearBackgrounds(debounceMs = debounceMs)

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds(debounceMs = debounceMs)

		// Disable blur on splashscreen
		_blurBackground.value = false

		try {
			// Manually grab the backdrop URL
			val api = jellyfin.createApi(baseUrl = server.address)
			val splashscreenUrl = api.imageApi.getSplashscreenUrl()
			val targetId = "server_${server.id}"

			requestBackgroundUpdate(
				targetId = targetId,
				backdropUrls = setOf(splashscreenUrl),
				debounceMs = debounceMs
			)
		} catch (err: Exception) {
			clearBackgrounds(debounceMs = debounceMs)
		}
	}

	/**
	 * Request a centralized debounced background update.
	 *
	 * Before the debounce: captures the [targetId] and updates [currentRequestedTargetId].
	 * After the debounce: verifies if [currentRequestedTargetId] still matches [targetId].
	 * If identical, applies the new background (unless it is similar to the already loaded background).
	 * If not identical, re-evaluates / restarts the debounce for the new requested target.
	 */
	private fun requestBackgroundUpdate(
		targetId: String,
		backdropUrls: Set<String>,
		debounceMs: Long = DEFAULT_DEBOUNCE_MS,
	) {
		// Before debounce: capture target item ID and update currentRequestedTargetId
		currentRequestedTargetId = targetId
		val capturedTargetId = targetId

		// Re-enable backgrounds if disabled
		_enabled.value = true

		// Check "sauf si il est similaire": if the requested target is already loaded and currently displayed
		if (capturedTargetId == currentlyLoadedTargetId &&
			_backgrounds.isNotEmpty() &&
			_currentBackground.value != null &&
			(backdropUrls.isEmpty() || backdropUrls == loadedUrls)
		) {
			if (pendingUrls != null && pendingUrls != loadedUrls) {
				loadBackgroundsJob?.cancel()
				pendingUrls = null
			}
			return
		}

		// If a load job is already active for this exact target and URLs, let it continue
		if (capturedTargetId == currentRequestedTargetId && backdropUrls == pendingUrls && loadBackgroundsJob?.isActive == true) {
			return
		}

		pendingUrls = backdropUrls

		// Cancel previous debounce job
		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			try {
				if (debounceMs > 0) {
					delay(debounceMs.milliseconds)
				}

				// After debounce: verify if target ID is still identical to current requested ID
				val latestRequestedId = currentRequestedTargetId
				if (latestRequestedId != capturedTargetId) {
					// Focus/item changed during debounce delay!
					// "sinon on relance le debounce" -> latest request handles its own debounce
					return@launch
				}

				// Verify again if target is similar / already loaded
				if (capturedTargetId == currentlyLoadedTargetId &&
					_backgrounds.isNotEmpty() &&
					_currentBackground.value != null &&
					(backdropUrls.isEmpty() || backdropUrls == loadedUrls)
				) {
					pendingUrls = null
					return@launch
				}

				if (backdropUrls.isEmpty()) {
					// Empty backdrops requested -> clear background
					_backgrounds = emptyList()
					loadedUrls = emptySet()
					currentlyLoadedTargetId = capturedTargetId
					pendingUrls = null
					_currentIndex = 0
					withContext(Dispatchers.Main) {
						_currentBackground.value = null
					}
					return@launch
				}

				// Fetch and decode first backdrop image immediately for instant feedback
				val firstUrl = backdropUrls.firstOrNull() ?: return@launch
				val firstBitmap = fetchImageBitmap(firstUrl) ?: return@launch

				// Check again after I/O loading if target ID is still the active requested target
				if (currentRequestedTargetId != capturedTargetId) {
					return@launch
				}

				_backgrounds = listOf(firstBitmap)
				loadedUrls = setOf(firstUrl)
				currentlyLoadedTargetId = capturedTargetId
				pendingUrls = null
				_currentIndex = 0
				showImmediateBackground()

				// Load remaining backdrop URLs in background if slideshow exists
				if (backdropUrls.size > 1) {
					val remainingUrls = backdropUrls.drop(1)
					val remainingBitmaps = remainingUrls.mapNotNull { url ->
						if (currentRequestedTargetId == capturedTargetId) fetchImageBitmap(url) else null
					}
					if (currentRequestedTargetId == capturedTargetId && remainingBitmaps.isNotEmpty()) {
						_backgrounds = listOf(firstBitmap) + remainingBitmaps
						loadedUrls = backdropUrls
					}
				}
			} catch (_: Exception) {
				// Coroutine cancelled; keep existing background visible until new selection completes
			}
		}
	}

	private suspend fun fetchImageBitmap(url: String): ImageBitmap? {
		return try {
			val request = ImageRequest.Builder(context)
				.data(url)
				.memoryCacheKey(url)
				.diskCacheKey(url)
				.bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
				.apply {
					if (android.os.Build.VERSION.SDK_INT < 31 && _blurBackground.value) {
						size(240, 135)
					}
				}
				.build()

			imageLoader.execute(request).image?.toBitmap()?.asImageBitmap()
		} catch (_: Exception) {
			null
		}
	}

	private fun showImmediateBackground() {
		lastBackgroundTimerUpdate = Instant.now().toEpochMilli()
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	@JvmOverloads
	fun clearBackgrounds(
		immediate: Boolean = false,
		debounceMs: Long = DEFAULT_DEBOUNCE_MS,
	) {
		val targetId = "none"
		currentRequestedTargetId = targetId

		if (immediate || debounceMs <= 0L) {
			loadBackgroundsJob?.cancel()
			pendingUrls = null
			loadedUrls = emptySet()
			currentlyLoadedTargetId = targetId
			_enabled.value = true
			if (_backgrounds.isEmpty()) return

			_backgrounds = emptyList()
			update()
			return
		}

		requestBackgroundUpdate(
			targetId = targetId,
			backdropUrls = emptySet(),
			debounceMs = debounceMs
		)
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}

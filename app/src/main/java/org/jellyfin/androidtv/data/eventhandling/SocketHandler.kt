package org.jellyfin.androidtv.data.eventhandling

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommand
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommands
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.LibraryUpdateInfo
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.extensions.get
import org.jellyfin.sdk.model.extensions.getValue
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.Instant
import java.time.Duration
import java.util.UUID

class SocketHandler(
	private val context: Context,
	private val api: ApiClient,
	private val dataRefreshService: DataRefreshService,
	private val mediaManager: MediaManager,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val navigationRepository: NavigationRepository,
	private val audioManager: AudioManager,
	private val itemLauncher: ItemLauncher,
	private val playbackHelper: PlaybackHelper,
	private val lifecycle: Lifecycle,
	private val preferencesRepository: PreferencesRepository,
) {
	init {
		lifecycle.coroutineScope.launch(Dispatchers.IO) {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				subscribe(this)
			}
		}
	}

	suspend fun updateSession() {
		try {
			withContext(Dispatchers.IO) {
				api.sessionApi.postCapabilities(
					playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
					supportsMediaControl = true,
					supportedCommands = buildList {
						add(GeneralCommandType.DISPLAY_CONTENT)
						add(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX)
						add(GeneralCommandType.SET_AUDIO_STREAM_INDEX)

						add(GeneralCommandType.DISPLAY_MESSAGE)
						add(GeneralCommandType.SEND_STRING)

						// Note: These are used in the PlaySessionSocketService
						if (!audioManager.isVolumeFixed) {
							add(GeneralCommandType.VOLUME_UP)
							add(GeneralCommandType.VOLUME_DOWN)
							add(GeneralCommandType.SET_VOLUME)

							add(GeneralCommandType.MUTE)
							add(GeneralCommandType.UNMUTE)
							add(GeneralCommandType.TOGGLE_MUTE)
						}
					},
				)
			}
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to update capabilities")
		}
	}

	var lastRefresh: Instant? = null
	val minRefreshInterval: Duration = Duration.ofSeconds(15)

	private fun subscribe(coroutineScope: CoroutineScope) = api.webSocket.apply {
		// Library
		subscribe<LibraryChangedMessage>()
			.onEach { message ->
				message.data?.let(::onLibraryChanged)
				//preferencesRepository.refreshServerUserSettings()
			}
			.launchIn(coroutineScope)

		// Media playback
		subscribe<PlayMessage>()
			.onEach { message -> onPlayMessage(message) }
			.launchIn(coroutineScope)

		subscribe<PlaystateMessage>()
			.onEach { message -> onPlayStateMessage(message) }
			.launchIn(coroutineScope)

		subscribe<UserDataChangedMessage>()
			/*
			.onEach { message ->
				Timber.d("Received UserDataChangedMessage")
				if (message.data?.userId == api.userApi.getCurrentUser().content.id){
					preferencesRepository.refreshServerUserSettings()
				}
			}
			 */
			.launchIn(coroutineScope)
/*
		subscribe<SessionsMessage>()
			.onEach { message ->
				Timber.d("Received SessionsMessage")
				//val now = Instant.now()
				//val elapsed = lastRefresh?.let { Duration.between(it, now) }
				//if (elapsed == null || elapsed > minRefreshInterval){
				//	lastRefresh = now
					preferencesRepository.refreshServerUserSettings()
				//}
			}
			.launchIn(coroutineScope)
 */

/*
		subscribeAll()
			.onEach { message ->
				Timber.i("Received type: %s", message.messageType)
			}
			.launchIn(coroutineScope)
 */

		subscribeGeneralCommand(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX)
			.onEach { message ->
				val index = message["index"]?.toIntOrNull() ?: return@onEach

				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.setSubtitleIndex(index)
				}
			}
			.launchIn(coroutineScope)

		subscribeGeneralCommand(GeneralCommandType.SET_AUDIO_STREAM_INDEX)
			.onEach { message ->
				val index = message["index"]?.toIntOrNull() ?: return@onEach

				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.switchAudioStream(index)
				}
			}
			.launchIn(coroutineScope)

		// General commands
		subscribeGeneralCommand(GeneralCommandType.DISPLAY_CONTENT)
			.onEach { message ->
				val itemId by message
				val itemType by message

				val itemUuid = itemId?.toUUIDOrNull()
				val itemKind = itemType?.let { type ->
					BaseItemKind.entries.find { value ->
						value.serialName.equals(type, true)
					}
				}

				if (itemUuid != null && itemKind != null) onDisplayContent(itemUuid, itemKind)
			}
			.launchIn(coroutineScope)

		subscribeGeneralCommands(setOf(GeneralCommandType.DISPLAY_MESSAGE, GeneralCommandType.SEND_STRING))
			.onEach { message ->
				val header by message
				val text by message
				val string by message

				onDisplayMessage(header, text ?: string)
			}
			.launchIn(coroutineScope)
	}

	private fun onLibraryChanged(info: LibraryUpdateInfo) {
		Timber.d(buildString {
			appendLine("Library changed.")
			appendLine("Added ${info.itemsAdded.size} items")
			appendLine("Removed ${info.itemsRemoved.size} items")
			appendLine("Updated ${info.itemsUpdated.size} items")
		})

		if (info.itemsAdded.any() || info.itemsRemoved.any())
			dataRefreshService.lastLibraryChange = Instant.now()
	}

	private fun onPlayMessage(message: PlayMessage) {
		val itemIds = message.data?.itemIds ?: return

		runCatching {
			playbackHelper.retrieveAndPlay(
				itemIds,
				false,
				message.data?.startPositionTicks,
				message.data?.startIndex,
				context
			)
		}.onFailure { Timber.w(it, "Failed to start playback") }
	}

	@Suppress("ComplexMethod")
	private suspend fun onPlayStateMessage(message: PlaystateMessage) = withContext(Dispatchers.Main) {
		Timber.i("Received PlayStateMessage with command ${message.data?.command}")

		// Audio playback uses (Rewrite)MediaManager, (legacy) video playback uses playbackController
		when {
			mediaManager.hasAudioQueueItems() -> {
				Timber.i("Ignoring PlayStateMessage: should be handled by PlaySessionSocketService")
				return@withContext
			}

			// PlaybackController
			else -> {
				val playbackController = playbackControllerContainer.playbackController
				when (message.data?.command) {
					PlaystateCommand.STOP -> playbackController?.endPlayback(true)
					PlaystateCommand.PAUSE, PlaystateCommand.UNPAUSE, PlaystateCommand.PLAY_PAUSE -> playbackController?.playPause()
					PlaystateCommand.NEXT_TRACK -> playbackController?.next()
					PlaystateCommand.PREVIOUS_TRACK -> playbackController?.prev()
					PlaystateCommand.SEEK -> playbackController?.seek(
						(message.data?.seekPositionTicks ?: 0) / TICKS_TO_MS
					)

					PlaystateCommand.REWIND -> playbackController?.rewind()
					PlaystateCommand.FAST_FORWARD -> playbackController?.fastForward()

					null -> Unit
				}
			}
		}
	}

	private suspend fun onDisplayContent(itemId: UUID, itemKind: BaseItemKind) = withContext(Dispatchers.Main) {
		val playbackController = playbackControllerContainer.playbackController

		if (playbackController?.isPlaying == true || playbackController?.isPaused == true) {
			Timber.i("Not launching $itemId: playback in progress")
			return@withContext
		}

		Timber.i("Launching $itemId")

		when (itemKind) {
			BaseItemKind.USER_VIEW,
			BaseItemKind.COLLECTION_FOLDER -> {
				val item by api.userLibraryApi.getItem(itemId = itemId)
				itemLauncher.launchUserView(item)
			}

			else -> navigationRepository.navigate(Destinations.itemDetails(itemId))
		}
	}

	private fun onDisplayMessage(header: String?, text: String?) {
		val toastMessage = buildString {
			if (!header.isNullOrBlank()) append(header, ": ")
			append(text)
		}

		runBlocking(Dispatchers.Main) {
			Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
		}
	}

	companion object {
		const val TICKS_TO_MS = 10000L
	}
}

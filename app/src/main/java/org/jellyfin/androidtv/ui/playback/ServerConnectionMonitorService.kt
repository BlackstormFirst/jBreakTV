package org.jellyfin.androidtv.ui.playback

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.ui.navigation.ActivityDestinations
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.systemApi
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ServerConnectionMonitorService(
	private val context: Context,
	private val api: ApiClient,
	private val sessionRepository: SessionRepository,
) : PlayerService() {

	private var activeMonitoringJob: Job? = null
	private var timeoutJob: Job? = null

	@Volatile
	private var isServerConnected = true

	@Volatile
	private var bufferEndedState = false

	@Volatile
	private var wasPlayingBeforeDisconnect = false

	private var lastPlayState: PlayState = PlayState.STOPPED

	fun isServerDisconnected(): Boolean = !isServerConnected || bufferEndedState

	override suspend fun onInitialize() {
		state.playState.onEach { playState ->
			if ((playState == PlayState.PLAYING) && !bufferEndedState) {
				wasPlayingBeforeDisconnect = true
			}

			if (playState != PlayState.STOPPED) {
				startMonitoring()
			} else {
				stopMonitoring()
				resetDisconnectionState()
			}
			lastPlayState = playState
		}.launchIn(coroutineScope)

		state.isBuffering.onEach { isBuffering ->
			if (isBuffering && !isServerConnected && !bufferEndedState) {
				coroutineScope.launch {
					if (!checkServerConnection()) {
						onBufferEndedUnderDisconnection()
					}
				}
			}
		}.launchIn(coroutineScope)

		manager.addListener(object : PlayerBackendEventListener() {
			override fun onError(error: Throwable) {
				if (!isServerConnected && !bufferEndedState) {
					coroutineScope.launch {
						if (!checkServerConnection()) {
							onBufferEndedUnderDisconnection()
						}
					}
				}
			}
		})
	}

	private var failedCheckCount = 0

	private fun startMonitoring() {
		if (activeMonitoringJob?.isActive == true) return

		activeMonitoringJob = coroutineScope.launch {
			while (true) {
				delay(3.seconds)
				if (state.playState.value == PlayState.STOPPED && manager.queue.estimatedSize == 0) {
					break
				}

				val connected = checkServerConnection()
				if (!connected) {
					failedCheckCount++
					if (failedCheckCount >= 2 && isServerConnected) {
						Timber.w("Server disconnection detected during active playback.")
						isServerConnected = false
					}

					if (!bufferEndedState && (state.isBuffering.value || state.playState.value == PlayState.ERROR)) {
						onBufferEndedUnderDisconnection()
					}
				} else {
					failedCheckCount = 0
					if (!isServerConnected) {
						Timber.i("Server connection restored.")
						isServerConnected = true
						onServerReconnected()
					}
				}
			}
		}
	}

	private fun stopMonitoring() {
		activeMonitoringJob?.cancel()
		activeMonitoringJob = null
	}

	private fun resetDisconnectionState() {
		timeoutJob?.cancel()
		timeoutJob = null
		failedCheckCount = 0
		isServerConnected = true
		bufferEndedState = false
		wasPlayingBeforeDisconnect = false
	}

	private suspend fun checkServerConnection(): Boolean {
		return try {
			withContext(Dispatchers.IO) {
				api.systemApi.getPublicSystemInfo()
				true
			}
		} catch (_: Exception) {
			false
		}
	}

	private suspend fun onBufferEndedUnderDisconnection() {
		if (bufferEndedState) return
		bufferEndedState = true

		Timber.w("End of buffer reached while server disconnected. Showing toast and starting 2-minute timer.")

		withContext(Dispatchers.Main) {
			Toast.makeText(context, R.string.server_connection_failed, Toast.LENGTH_LONG).show()
		}

		if (wasPlayingBeforeDisconnect || state.playState.value == PlayState.PLAYING) {
			wasPlayingBeforeDisconnect = true
			state.pause()
		}

		timeoutJob?.cancel()
		timeoutJob = coroutineScope.launch {
			delay(2.minutes)
			onTwoMinuteTimeout()
		}
	}

	private suspend fun onServerReconnected() {
		timeoutJob?.cancel()
		timeoutJob = null

		if (bufferEndedState) {
			bufferEndedState = false
			withContext(Dispatchers.Main) {
				Toast.makeText(context, R.string.server_connection_restored, Toast.LENGTH_SHORT).show()
			}

			if (wasPlayingBeforeDisconnect) {
				wasPlayingBeforeDisconnect = false
				state.unpause()
			}
		}
	}

	private suspend fun onTwoMinuteTimeout() {
		Timber.w("2 minutes elapsed without server reconnection. Returning to profile selection.")
		resetDisconnectionState()

		state.stop()

		withContext(Dispatchers.Main) {
			Toast.makeText(context, R.string.server_connection_failed, Toast.LENGTH_LONG).show()

			sessionRepository.destroyCurrentSession()

			val intent = ActivityDestinations.startup(context)
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
			context.startActivity(intent)
		}
	}
}

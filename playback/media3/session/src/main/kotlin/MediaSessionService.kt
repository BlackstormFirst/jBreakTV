package org.jellyfin.playback.media3.session

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.await
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.metadata
import org.jellyfin.playback.core.queue.queue
import timber.log.Timber

class MediaSessionService(
	private val androidContext: Context,
	private val options: MediaSessionOptions,
) : PlayerService() {
	private val notificationManager = NotificationManagerCompat.from(androidContext)
	private var notifiedNotificationId: Int? = null
	private var mediaSession: MediaSession? = null
	private var sessionPlayer: MediaSessionPlayer? = null

	override suspend fun onInitialize() {
		releaseSession()

		val player = MediaSessionPlayer(
			looper = Looper.getMainLooper(),
			scope = coroutineScope,
			state = state,
			manager = manager,
		).also { sessionPlayer = it }

		val callback = object : MediaSession.Callback {
			@Deprecated("Deprecated in Java")
			@Suppress("DEPRECATION")
			override fun onPlayerCommandRequest(
				session: MediaSession,
				controller: MediaSession.ControllerInfo,
				playerCommand: Int,
			): Int {
				if (playerCommand == androidx.media3.common.Player.COMMAND_STOP) {
					state.pause()
				}
				return super.onPlayerCommandRequest(session, controller, playerCommand)
			}

			@OptIn(UnstableApi::class)
			override fun onMediaButtonEvent(
				session: MediaSession,
				controllerInfo: MediaSession.ControllerInfo,
				intent: android.content.Intent,
			): Boolean {
				@Suppress("DEPRECATION")
				val keyEvent = intent.getParcelableExtra<android.view.KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)
				if (keyEvent?.action == android.view.KeyEvent.ACTION_DOWN) {
					if (keyEvent.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP) {
						state.pause()
						return true
					}
				}
				return super.onMediaButtonEvent(session, controllerInfo, intent)
			}
		}

		val session = MediaSession.Builder(androidContext, player).apply {
			setId(options.notificationId.toString())
			setSessionActivity(options.openIntent)
			setCallback(callback)
		}.build().also { mediaSession = it }

		AndroidMediaService.start(androidContext, session)

		manager.queue.entry.onEach { item ->
			if (item != null) {
				updateNotification(session, item)
			} else {
				if (notifiedNotificationId != null) {
					notificationManager.cancel(notifiedNotificationId!!)
					notifiedNotificationId = null
				}
				AndroidMediaService.stop(androidContext)
			}
		}.launchIn(coroutineScope)
	}

	@OptIn(UnstableApi::class)
	fun releaseSession() {
		runCatching {
			mediaSession?.let { AndroidMediaService.stop(androidContext) }
			mediaSession?.release()
			sessionPlayer?.release()
		}
		mediaSession = null
		sessionPlayer = null
		if (notifiedNotificationId != null) {
			notificationManager.cancel(notifiedNotificationId!!)
			notifiedNotificationId = null
		}
	}

	@OptIn(UnstableApi::class)
	private suspend fun updateNotification(session: MediaSession, item: QueueEntry) {
		val notification = NotificationCompat.Builder(androidContext, options.channelId).apply {
			// Set metadata
			setContentTitle(item.metadata.title)
			setContentText(item.metadata.artist)

			// Set actions
			setContentIntent(options.openIntent)
			setStyle(MediaStyleNotificationHelper.MediaStyle(session))

			// Make visible on lock screen
			setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

			// Set flags
			setOnlyAlertOnce(true)
			setOngoing(false)

			// Add branding & art
			setSmallIcon(options.iconSmall)
			item.metadata.artworkUri?.let { uriString ->
				val artworkUri = runCatching { uriString.toUri() }.getOrNull()
				if (artworkUri != null) {
					runCatching {
						session.bitmapLoader.loadBitmap(artworkUri).await()
					}.fold(
						onSuccess = ::setLargeIcon,
						onFailure = { error -> Timber.w(error, "Failed to retrieve artwork") },
					)
				}
			}
		}.build()

		if (notifiedNotificationId == null) notifiedNotificationId = options.notificationId

		// POST_NOTIFICATIONS permission is not required for media notifications
		@Suppress("MissingPermission")
		notificationManager.notify(notifiedNotificationId!!, notification)
	}
}

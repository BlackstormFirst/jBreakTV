package org.jellyfin.playback.media3.session

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import timber.log.Timber

class AndroidMediaService : MediaSessionService() {

	companion object {
		private var activeSession: MediaSession? = null
		private var serviceInstance: AndroidMediaService? = null

		fun start(context: Context, session: MediaSession) {
			activeSession = session
			serviceInstance?.let { instance ->
				if (!instance.sessions.contains(session)) {
					instance.addSession(session)
				}
			}
			val intent = Intent(context, AndroidMediaService::class.java)
			runCatching {
				context.startService(intent)
			}.onFailure { error ->
				Timber.w(error, "Failed to start AndroidMediaService")
			}
		}

		fun stop(context: Context) {
			activeSession?.let { session ->
				serviceInstance?.removeSession(session)
			}
			activeSession = null
			val intent = Intent(context, AndroidMediaService::class.java)
			runCatching {
				context.stopService(intent)
			}
		}
	}

	override fun onCreate() {
		super.onCreate()
		serviceInstance = this
		activeSession?.let { session ->
			if (!sessions.contains(session)) {
				addSession(session)
			}
		}
	}

	override fun onDestroy() {
		serviceInstance = null
		super.onDestroy()
	}

	override fun onGetSession(
		controllerInfo: MediaSession.ControllerInfo,
	): MediaSession? = activeSession ?: sessions.firstOrNull()
}

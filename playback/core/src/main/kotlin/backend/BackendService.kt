package org.jellyfin.playback.core.backend

import androidx.core.view.doOnDetach
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView

import timber.log.Timber

/**
 * Service keeping track of the current playback backend and its related surface view.
 */
class BackendService {
	private var _backend: PlayerBackend? = null
	val backend get() = _backend

	private var listeners = mutableListOf<PlayerBackendEventListener>()
	private var _surfaceView: PlayerSurfaceView? = null
	val surfaceView get() = _surfaceView
	private var _subtitleView: PlayerSubtitleView? = null

	fun switchBackend(backend: PlayerBackend) {
		Timber.d("PlayerDebug [BackendService.switchBackend]: Switching backend to $backend")
		_backend?.stop()
		_backend?.release()
		_backend?.setListener(null)
		_backend?.setSurfaceView(null)
		_backend?.setSubtitleView(null)

		_backend = backend.apply {
			_surfaceView?.let(::setSurfaceView)
			_subtitleView?.let(::setSubtitleView)
			setListener(BackendEventListener())
		}
	}

	fun release() {
		Timber.d("PlayerDebug [BackendService.release]: Releasing BackendService")
		_backend?.stop()
		_backend?.release()
		_backend?.setListener(null)
		_backend?.setSurfaceView(null)
		_backend?.setSubtitleView(null)
		_backend = null
		_surfaceView = null
		_subtitleView = null
		listeners.clear()
	}

	fun attachSurfaceView(surfaceView: PlayerSurfaceView) {
		Timber.d("PlayerDebug [BackendService.attachSurfaceView]: Attaching surfaceView hash=${surfaceView.hashCode()}")
		// Apply new surface view
		_surfaceView = surfaceView.apply {
			_backend?.setSurfaceView(surfaceView)

			// Automatically detach when surface view is detached from window
			doOnDetach {
				if (surfaceView == _surfaceView && !surfaceView.isAttachedToWindow) {
					Timber.d("PlayerDebug [BackendService.attachSurfaceView.doOnDetach]: Detaching surfaceView hash=${surfaceView.hashCode()}")
					_surfaceView = null
					_backend?.setSurfaceView(null)
				}
			}
		}

		listeners.forEach { listener ->
			listener.onSurfaceAttached(surfaceView)
		}
	}

	fun attachSubtitleView(subtitleView: PlayerSubtitleView) {
		// Remove existing surface view
		if (_subtitleView != null) {
			_backend?.setSubtitleView(null)
		}

		// Apply new surface view
		_subtitleView = subtitleView.apply {
			_backend?.setSubtitleView(subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _subtitleView) {
					_subtitleView = null
					_backend?.setSubtitleView(null)
				}
			}
		}
	}

	fun addListener(listener: PlayerBackendEventListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: PlayerBackendEventListener) {
		listeners.remove(listener)
	}

	inner class BackendEventListener : PlayerBackendEventListener() {
		private fun <T> callListeners(
			body: PlayerBackendEventListener.() -> T
		): List<T> = listeners.map { listener -> listener.body() }

		override fun onPlayStateChange(state: PlayState) {
			callListeners { onPlayStateChange(state) }
		}

		override fun onBufferingStateChange(isBuffering: Boolean) {
			callListeners { onBufferingStateChange(isBuffering) }
		}

		override fun onVideoSizeChange(width: Int, height: Int, frameRate: Float) {
			callListeners { onVideoSizeChange(width, height, frameRate) }
		}

		override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
			callListeners { onMediaStreamEnd(mediaStream) }
		}

		override fun onTracksChanged() {
			callListeners { onTracksChanged() }
		}

		override fun onError(error: Throwable) {
			callListeners { onError(error) }
		}

		override fun onSurfaceAttached(surfaceView: PlayerSurfaceView) {
			callListeners { onSurfaceAttached(surfaceView) }
		}
	}
}

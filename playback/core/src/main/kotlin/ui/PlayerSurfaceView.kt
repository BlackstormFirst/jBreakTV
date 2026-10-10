package org.jellyfin.playback.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import org.jellyfin.playback.core.PlaybackManager

import timber.log.Timber

/**
 * A view that is used to display the video output of the playing media.
 * The [playbackManager] must be set when the view is initialized.
 */
class PlayerSurfaceView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	var playbackManager: PlaybackManager? = null
		set(value) {
			field = value
			if (value != null && isAttachedToWindow && !isInEditMode) {
				attachToBackend()
			}
		}

	val surface = SurfaceView(context, attrs).apply {
		addView(this, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
		holder.addCallback(object : SurfaceHolder.Callback {
			override fun surfaceCreated(holder: SurfaceHolder) {
				Timber.d("PlayerDebug [PlayerSurfaceView.surfaceCreated]: Surface created for hash=${this@PlayerSurfaceView.hashCode()}, isValid=${holder.surface.isValid}")
				attachToBackend()
			}

			override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
				Timber.d("PlayerDebug [PlayerSurfaceView.surfaceChanged]: Surface changed to ${width}x${height} for hash=${this@PlayerSurfaceView.hashCode()}")
				attachToBackend()
			}

			override fun surfaceDestroyed(holder: SurfaceHolder) {
				Timber.d("PlayerDebug [PlayerSurfaceView.surfaceDestroyed]: Surface destroyed for hash=${this@PlayerSurfaceView.hashCode()}")
			}
		})
	}

	fun attachToBackend() {
		if (!isInEditMode) {
			Timber.d("PlayerDebug [PlayerSurfaceView.attachToBackend]: Attaching PlayerSurfaceView hash=${this.hashCode()} to backend")
			playbackManager?.backendService?.attachSurfaceView(this)
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		Timber.d("PlayerDebug [PlayerSurfaceView.onAttachedToWindow]: Attached hash=${this.hashCode()} to window")
		attachToBackend()
	}

	override fun onDetachedFromWindow() {
		super.onDetachedFromWindow()
		Timber.d("PlayerDebug [PlayerSurfaceView.onDetachedFromWindow]: Detached hash=${this.hashCode()} from window")
	}
}

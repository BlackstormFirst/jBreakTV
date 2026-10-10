package org.jellyfin.playback.core.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import org.jellyfin.playback.core.PlaybackManager

/**
 * A view that is used to display the subtitle output of the playing media.
 * The [playbackManager] must be set when the view is initialized.
 */
class PlayerSubtitleView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
	defStyleRes: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr, defStyleRes) {
	var playbackManager: PlaybackManager? = null
		set(value) {
			val changed = field != value
			field = value
			if (value != null && isAttachedToWindow && !isInEditMode && changed) {
				value.backendService.attachSubtitleView(this)
			}
		}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()

		if (!isInEditMode) {
			playbackManager?.backendService?.attachSubtitleView(this)
		}
	}
}


package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.PlaybackControlsRow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter

class PlayPauseAction(context: Context) : PlaybackControlsRow.PlayPauseAction(context),
	AndroidAction {

	private val animatedDrawable = AppCompatResources.getDrawable(context, R.drawable.ica_play_pause)

	init {
		animatedDrawable?.let { drawable ->
			setDrawables(arrayOf(drawable, drawable))
		}
	}

	override fun setIndex(index: Int) {
		super.setIndex(index)
		val isActivated = index == INDEX_PAUSE
		animatedDrawable?.state = if (isActivated) intArrayOf(android.R.attr.state_activated) else intArrayOf()
	}

	override fun onActionClicked(videoPlayerAdapter: VideoPlayerAdapter) =
		when (this.index) {
			INDEX_PLAY -> {
				videoPlayerAdapter.play()
				this.index = INDEX_PAUSE
			}

			INDEX_PAUSE -> {
				videoPlayerAdapter.pause()
				this.index = INDEX_PLAY
			}

			else -> {}
		}

}

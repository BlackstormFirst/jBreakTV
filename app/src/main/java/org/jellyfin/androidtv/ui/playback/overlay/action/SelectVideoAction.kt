package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.sdk.model.api.MediaStreamType

class SelectVideoAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_video)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		val videoTracks = playbackController.currentStreamInfo?.getSelectableStreams(MediaStreamType.VIDEO) ?: return
		val currentVideoIndex = playbackController.videoStreamIndex

		dismissPopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			with(menu) {
				for (track in videoTracks) {
					add(0, track.index, track.index, track.displayTitle).apply {
						isChecked = currentVideoIndex == track.index
					}
				}
				setGroupCheckable(0, true, true)
			}

			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				playbackController.switchVideoStream(item.itemId)
				true
			}
		}
		popup?.show()
	}

	fun dismissPopup() {
		popup?.dismiss()
	}
}

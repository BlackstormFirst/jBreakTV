package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto

interface AudioEventListener {
	fun onPlaybackStateChange(isPlaying: Boolean, currentItem: BaseItemDto?) = Unit
	fun onProgress(pos: Long, duration: Long) = Unit
	fun onQueueStatusChanged(hasQueue: Boolean) = Unit
	fun onQueueReplaced() = Unit
}

package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState

abstract class PlayerBackendEventListener {
	open fun onPlayStateChange(state: PlayState) = Unit
	open fun onBufferingStateChange(isBuffering: Boolean) = Unit
	open fun onVideoSizeChange(width: Int, height: Int, frameRate: Float = 0f) = Unit
	open fun onMediaStreamEnd(mediaStream: PlayableMediaStream) = Unit
	open fun onTracksChanged() = Unit
}

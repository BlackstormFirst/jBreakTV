package org.jellyfin.playback.core.model

data class VideoSize(
	val width: Int,
	val height: Int,
	val frameRate: Float = 0f,
) {
	val aspectRatio: Float get() = (width.toFloat() / height.toFloat()).takeIf { !it.isNaN() } ?: 0f

	companion object {
		val EMPTY = VideoSize(0, 0, 0f)
	}
}

package org.jellyfin.playback.core

/**
 * Libass subtitle rendering mode.
 */
enum class LibassRenderMode {
	/**
	 * Standard / time-based rendering mode (original behavior).
	 */
	STANDARD,

	/**
	 * Frame-accurate rendering mode using video frame metadata timestamps.
	 */
	FRAME_ACCURATE,
}

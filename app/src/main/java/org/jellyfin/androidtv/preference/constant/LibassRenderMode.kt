package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.playback.core.LibassRenderMode

val LibassRenderMode.nameRes: Int
	get() = when (this) {
		LibassRenderMode.STANDARD -> R.string.libass_render_mode_standard
		LibassRenderMode.FRAME_ACCURATE -> R.string.libass_render_mode_frame_accurate
	}

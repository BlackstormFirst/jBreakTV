package org.jellyfin.androidtv.data.compat

class VideoOptions : AudioOptions() {
	var audioStreamIndex: Int? = null
	var videoStreamIndex: Int? = null
	var subtitleStreamIndex: Int? = null
	var alwaysBurnInSubtitleWhenTranscoding: Boolean = false
}

package org.jellyfin.androidtv.util.apiclient

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

object StreamHelper {
    @JvmStatic
    fun getSubtitleStreams(mediaSource: MediaSourceInfo?): List<MediaStream> {
        return getStreams(mediaSource, MediaStreamType.SUBTITLE)
    }

    @JvmStatic
    fun getAudioStreams(mediaSource: MediaSourceInfo?): List<MediaStream> {
        return getStreams(mediaSource, MediaStreamType.AUDIO)
    }

    @JvmStatic
    private fun getStreams(mediaSource: MediaSourceInfo?, type: MediaStreamType): List<MediaStream> {
        if (mediaSource == null) return emptyList()

        val streams = mediaSource.mediaStreams ?: return emptyList()
        val ret = ArrayList<MediaStream>()
        for (stream in streams) {
            if (stream.type == type) {
                ret.add(stream)
            }
        }
        return ret
    }
}

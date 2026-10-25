package org.jellyfin.androidtv.data.compat

import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID

class StreamInfo {
    var itemId: UUID? = null
    var mediaUrl: String? = null
    var playMethod: PlayMethod = PlayMethod.DIRECT_PLAY
    var container: String? = null
    var runTimeTicks: Long? = null
    var mediaSource: MediaSourceInfo? = null
    var playSessionId: String? = null

    val subtitleDeliveryMethod: SubtitleDeliveryMethod
        get() {
            val source = mediaSource ?: return SubtitleDeliveryMethod.DROP
            val subtitleStreamIndex = source.defaultSubtitleStreamIndex
            if (subtitleStreamIndex == null || subtitleStreamIndex == -1 || source.mediaStreams == null) return SubtitleDeliveryMethod.DROP
            val stream = source.mediaStreams?.getOrNull(subtitleStreamIndex)
            return stream?.deliveryMethod ?: SubtitleDeliveryMethod.DROP
        }

    val mediaSourceId: String?
        get() = mediaSource?.id

    val selectableAudioStreams: ArrayList<MediaStream>
        get() = getSelectableStreams(MediaStreamType.AUDIO)

    fun getSelectableStreams(type: MediaStreamType): ArrayList<MediaStream> {
        val list = ArrayList<MediaStream>()
        val streams = mediaSource?.mediaStreams ?: return list
        for (stream in streams) {
            if (type == stream.type) {
                list.add(stream)
            }
        }
        return list
    }
}

package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.queue.preferredAudioIndex
import org.jellyfin.playback.core.queue.preferredSubtitleIndex
import org.jellyfin.playback.core.queue.preferredVideoIndex
import org.koin.compose.koinInject

@Composable
fun PlaybackInfoOverlay(
	playbackManager: PlaybackManager,
	modifier: Modifier = Modifier,
) {
	val userPreferences = koinInject<UserPreferences>()
	val entry by rememberQueueEntry(playbackManager)
	val mediaStream by entry?.mediaStreamFlow?.collectAsState(null) ?: return
	val stream = mediaStream ?: return

	val activeVideoIndex = entry?.preferredVideoIndex
	val videoTrack = stream.tracks.filterIsInstance<MediaStreamVideoTrack>()
		.firstOrNull { activeVideoIndex == null || it.index == activeVideoIndex }
		?: stream.tracks.filterIsInstance<MediaStreamVideoTrack>().firstOrNull()

	val activeAudioIndex = entry?.preferredAudioIndex
	val audioTrack = stream.tracks.filterIsInstance<MediaStreamAudioTrack>()
		.firstOrNull { activeAudioIndex == null || it.index == activeAudioIndex }
		?: stream.tracks.filterIsInstance<MediaStreamAudioTrack>().firstOrNull()

	val activeSubIndex = entry?.preferredSubtitleIndex
	val subtitleTrack = if (activeSubIndex != null && activeSubIndex >= 0) {
		stream.tracks.filterIsInstance<MediaStreamSubtitleTrack>()
			.firstOrNull { it.index == activeSubIndex }
	} else {
		null
	}

	val isLibassEnabled = userPreferences[UserPreferences.assDirectPlay]

	Column(
		verticalArrangement = Arrangement.spacedBy(2.dp),
		modifier = modifier
			.background(Color.Black.copy(alpha = 0.8f))
			.padding(12.dp)
	) {
		// Play method
		val methodStr = when (stream.conversionMethod) {
			MediaConversionMethod.None -> stringResource(R.string.playback_info_direct_play)
			MediaConversionMethod.Remux -> stringResource(R.string.playback_info_direct_stream)
			MediaConversionMethod.Transcode -> stringResource(R.string.playback_info_transcoding)
		}
		InfoText(stringResource(R.string.playback_info_play_method, methodStr))

		// Container
		InfoText(stringResource(R.string.playback_info_container, stream.container.format.uppercase()))

		// Libass state
		val libassStatus = if (isLibassEnabled) stringResource(R.string.state_enabled) else stringResource(R.string.state_disabled)
		InfoText(stringResource(R.string.playback_info_libass, libassStatus))

		// Video info
		if (videoTrack != null) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_video_title))
			val videoTitle = videoTrack.displayTitle ?: videoTrack.title
			if (!videoTitle.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_title, videoTitle))
			}
			InfoText(stringResource(R.string.playback_info_codec, videoTrack.codec.uppercase()))
			if (videoTrack.width > 0 && videoTrack.height > 0) {
				InfoText(stringResource(R.string.playback_info_resolution, videoTrack.width, videoTrack.height))
			}
			if (videoTrack.bitrate > 0) {
				InfoText(stringResource(R.string.playback_info_bitrate, formatBitrate(videoTrack.bitrate)))
			}
			val videoRange = videoTrack.videoRange
			if (!videoRange.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_video_range, videoRange))
			}
		}

		// Audio info
		if (audioTrack != null) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_audio_title))
			val audioTitle = audioTrack.displayTitle ?: audioTrack.title
			if (!audioTitle.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_title, audioTitle))
			}
			val audioLang = audioTrack.language
			if (!audioLang.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_language, audioLang))
			}
			InfoText(stringResource(R.string.playback_info_codec, audioTrack.codec.uppercase()))
			InfoText(stringResource(R.string.playback_info_channels, audioTrack.channels))
			if (audioTrack.sampleRate > 0) {
				InfoText(stringResource(R.string.playback_info_sample_rate, formatSampleRate(audioTrack.sampleRate)))
			}
			if (audioTrack.bitrate > 0) {
				InfoText(stringResource(R.string.playback_info_bitrate, formatBitrate(audioTrack.bitrate)))
			}
			if (audioTrack.isDefault) {
				InfoText(stringResource(R.string.playback_info_default, stringResource(R.string.state_enabled)))
			}
		}

		// Subtitle info
		InfoText("")
		InfoText(stringResource(R.string.playback_info_subtitle_title))
		if (subtitleTrack != null) {
			val subTitle = subtitleTrack.displayTitle ?: subtitleTrack.title
			if (!subTitle.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_title, subTitle))
			}
			val subLang = subtitleTrack.language
			if (!subLang.isNullOrBlank()) {
				InfoText(stringResource(R.string.playback_info_language, subLang))
			}
			if (subtitleTrack.codec.isNotBlank()) {
				InfoText(stringResource(R.string.playback_info_codec, subtitleTrack.codec.uppercase()))
			}
			val extStr = if (subtitleTrack.isExternal) stringResource(R.string.state_enabled) else stringResource(R.string.state_disabled)
			InfoText(stringResource(R.string.playback_info_external, extStr))
			if (subtitleTrack.isDefault) {
				InfoText(stringResource(R.string.playback_info_default, stringResource(R.string.state_enabled)))
			}
			if (subtitleTrack.isForced) {
				InfoText(stringResource(R.string.playback_info_forced, stringResource(R.string.state_enabled)))
			}
		} else {
			InfoText(stringResource(R.string.playback_info_none))
		}

		// Transcoding note
		if (stream.conversionMethod == MediaConversionMethod.Transcode) {
			InfoText("")
			InfoText(stringResource(R.string.playback_info_transcoding_note))
		}
	}
}

@Composable
private fun formatBitrate(bitrate: Int): String {
	val mbitStr = stringResource(R.string.bitrate_mbit, 0f).substringAfter("0")
	val kbitStr = stringResource(R.string.bitrate_kbit, 0f).substringAfter("0")
	return when {
		bitrate >= 1_000_000 -> "%.1f$mbitStr".format(bitrate / 1_000_000f)
		else -> "%.0f$kbitStr".format(bitrate / 1_000f)
	}
}

private fun formatSampleRate(sampleRate: Int): String {
	return if (sampleRate >= 1000) {
		if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz"
		else "%.1f kHz".format(sampleRate / 1000f)
	} else {
		"$sampleRate Hz"
	}
}

@Composable
private fun InfoText(text: String) {
	Text(
		text = text,
		style = TextStyle(
			color = Color.White,
			fontSize = 7.sp,
		)
	)
}

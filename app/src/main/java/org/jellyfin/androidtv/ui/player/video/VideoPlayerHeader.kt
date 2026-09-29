package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
	playbackManager: PlaybackManager,
) {
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	val remainingDuration = (positionInfo.duration - positionInfo.active).coerceAtLeast(Duration.ZERO)

	val finishText = remember(positionInfo) {
		if (remainingDuration > Duration.ZERO) {
			val finishTime = LocalTime.now().plusSeconds(remainingDuration.inWholeSeconds)
			val formatter = DateTimeFormatter.ofPattern("HH:mm")
			finishTime.format(formatter)
		} else null
	}

	val isLocal = item?.let { LocalVideoManager.isLocalItem(it) } == true
	val isEpisode = item?.type == BaseItemKind.EPISODE || item?.indexNumber != null

	val titleText = remember(item, isLocal, isEpisode) {
		val episodeName = item?.name.orEmpty()
		if (!isLocal && isEpisode) {
			val seasonNum = item.parentIndexNumber
			val episodeNum = item.indexNumber
			if (seasonNum != null && episodeNum != null) {
				val sFormatted = "%02d".format(seasonNum)
				val eFormatted = "%02d".format(episodeNum)
				"S${sFormatted}E$eFormatted: $episodeName"
			} else if (episodeNum != null) {
				val eFormatted = "%02d".format(episodeNum)
				"E${eFormatted}: $episodeName"
			} else {
				episodeName
			}
		} else {
			episodeName
		}
	}

	PlayerHeader {
		if (item != null) {
			Text(
				text = titleText,
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				style = LocalTextStyle.current.copy(
					color = Color.White,
					fontSize = 22.sp
				)
			)

			if (!item.seriesName.isNullOrEmpty()) {
				Text(
					text = item.seriesName.orEmpty(),
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White.copy(alpha = 0.8f),
						fontSize = 18.sp
					)
				)
			}

			if (finishText != null) {
				Text(
					text = stringResource(R.string.lbl_playback_control_ends, finishText),
					style = LocalTextStyle.current.copy(
						color = Color.White.copy(alpha = 0.7f),
						fontSize = 14.sp
					)
				)
			}

		}
	}
}

package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.usbdevices.LocalVideoManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject

@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
	imageHelper: ImageHelper = koinInject(),
) {
	val isLocal = item?.let { LocalVideoManager.isLocalItem(it) } == true
	val isLiveTv = LiveTvPlayerHelper.isLiveTv(item)
	val isEpisode = item?.type == BaseItemKind.EPISODE || item?.indexNumber != null

	val logoUrl = remember(item, imageHelper, isLiveTv) {
		item?.let {
			val logo = imageHelper.getLogoImageUrl(it, maxWidth = 440)
			if (logo == null && isLiveTv) {
				imageHelper.getPrimaryImageUrl(it, preferParentThumb = false)
			} else {
				logo
			}
		}
	}

	val titleText = remember(item, isLocal, isLiveTv, isEpisode) {
		var name = item?.name.orEmpty()
		if (isLocal && name.isNotEmpty()) {
			val lastDot = name.lastIndexOf('.')
			if (lastDot > 0) {
				val ext = name.substring(lastDot + 1).lowercase()
				if (ext in listOf("mkv", "mp4", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "m4v")) {
					name = name.substring(0, lastDot)
				}
			}
		}
		if (item != null && isLiveTv) {
			val channelNum = item.channelNumber ?: item.number
			if (!channelNum.isNullOrEmpty()) "$channelNum $name" else name
		} else if (item != null && !isLocal && isEpisode) {
			val seasonNum = item.parentIndexNumber
			val episodeNum = item.indexNumber
			if (seasonNum != null && episodeNum != null) {
				val sFormatted = "%02d".format(seasonNum)
				val eFormatted = "%02d".format(episodeNum)
				"S${sFormatted}E$eFormatted: $name"
			} else if (episodeNum != null) {
				val eFormatted = "%02d".format(episodeNum)
				"E${eFormatted}: $name"
			} else {
				name
			}
		} else {
			name
		}
	}

	val subtitleText = remember(item, isLiveTv) {
		if (item != null && isLiveTv) {
			item.currentProgram?.name ?: item.overview ?: item.seriesName.orEmpty()
		} else {
			item?.seriesName.orEmpty()
		}
	}

	PlayerHeader {
		if (item != null) {
			if (titleText.isNotEmpty()) {
				Text(
					text = titleText,
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White,
						fontSize = 22.sp
					)
				)
			}

			if (subtitleText.isNotEmpty()) {
				Text(
					text = subtitleText,
					overflow = TextOverflow.Ellipsis,
					maxLines = 1,
					style = LocalTextStyle.current.copy(
						color = Color.White.copy(alpha = 0.8f),
						fontSize = 18.sp
					)
				)
			}

			if (logoUrl != null) {
				Image(
					painter = rememberAsyncImagePainter(
						ImageRequest.Builder(LocalContext.current)
							.data(logoUrl)
							.build()
					),
					contentDescription = titleText,
					modifier = Modifier
						.height(60.dp)
						.widthIn(max = 450.dp),
					contentScale = ContentScale.Fit,
				)
			}
		}
	}
}

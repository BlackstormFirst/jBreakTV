package org.jellyfin.androidtv.ui.presentation.config

import android.widget.ImageView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.sdk.model.api.BaseItemKind

data class BaseRowItemDisplayConfig(
	val image: JellyfinImage?,
	val iconRes: Int,
	val aspectRatio: Float,
	val overrideShowInfo: Boolean? = null,
	val scaleType: ImageView.ScaleType? = null,
)

fun BaseRowItem.getDisplayConfig(imageType: ImageType, uniformAspect: Boolean, displayCirclePersonCards: Boolean): BaseRowItemDisplayConfig = when (baseRowType) {
	BaseRowType.BaseItem -> {
		val isRecording = this is BaseItemDtoBaseRowItem && isRecordingItem
		if (isRecording) {
			val image = getImage(imageType)
			BaseRowItemDisplayConfig(
				aspectRatio = when (imageType) {
					ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
					else -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				},
				image = image,
				iconRes = R.drawable.ic_tv,
				overrideShowInfo = true,
			)
		} else {
			val preferSeriesPoster = this is BaseItemDtoBaseRowItem && preferSeriesPoster
			val primaryAspectRatio = baseItem?.primaryImageAspectRatio?.toFloat()
		val defaultAspectRatio = when {
			preferParentThumb && (baseItem?.parentThumbItemId != null || baseItem?.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.EPISODE && primaryAspectRatio != null -> primaryAspectRatio
			baseItem?.type == BaseItemKind.EPISODE && (baseItem.parentThumbItemId != null || baseItem.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.USER_VIEW -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> primaryAspectRatio ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		}

		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = getImage(imageType),
			iconRes = R.drawable.ic_clapperboard,
		)

		when (baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST -> base.copy(
				iconRes = R.drawable.ic_music_album,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.PERSON,
			BaseItemKind.MUSIC_ARTIST -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.SEASON, BaseItemKind.SERIES -> base.copy(
				aspectRatio = when (imageType) {
					ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
					ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
					else -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
				},
				iconRes = R.drawable.ic_tv
			)

			BaseItemKind.EPISODE -> when (preferSeriesPoster) {
				true -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
					iconRes = R.drawable.ic_tv
				)

				false -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
					iconRes = R.drawable.ic_tv,
					overrideShowInfo = true,
				)
			}

			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> base.copy(
				aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE -> base.copy(
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.PHOTO -> base.copy(
				iconRes = R.drawable.ic_photo
			)

			BaseItemKind.PHOTO_ALBUM -> base.copy(
				iconRes = R.drawable.ic_folder
			)

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> {
				val image = getImage(imageType)
				val hasThumbImage = (this is BaseItemDtoBaseRowItem && preferMovieThumb) &&
					(image?.type == org.jellyfin.sdk.model.api.ImageType.THUMB || image?.type == org.jellyfin.sdk.model.api.ImageType.BACKDROP)
				base.copy(
					image = image,
					aspectRatio = when {
						imageType == ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
						imageType == ImageType.THUMB || hasThumbImage -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
						else -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					},
					iconRes = R.drawable.ic_clapperboard,
				)
			}

			else -> base
		}
	}
}

	BaseRowType.LiveTvChannel -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: 1f
		},
		image = getImage(imageType),
		scaleType = ImageView.ScaleType.FIT_CENTER,
		iconRes = R.drawable.ic_tv,
	)

	BaseRowType.LiveTvProgram -> BaseRowItemDisplayConfig(
		aspectRatio = when (imageType) {
			ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
			ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> baseItem?.primaryImageAspectRatio?.toFloat() ?: ImageHelper.ASPECT_RATIO_16_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_tv,
		overrideShowInfo = true,
	)

	BaseRowType.LiveTvRecording -> {
		val image = getImage(imageType)
		BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
				else -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			},
			image = image,
			iconRes = R.drawable.ic_tv,
			overrideShowInfo = true,
		)
	}

	BaseRowType.SeriesTimer -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		iconRes = R.drawable.ic_tv_timer,
		image = getImage(imageType),
		overrideShowInfo = true,
	)

	BaseRowType.Person -> BaseRowItemDisplayConfig(
		aspectRatio = if (displayCirclePersonCards) {
			1.0f
		} else {
			ImageHelper.ASPECT_RATIO_7_9.toFloat()
		},
		image = getImage(imageType),
		iconRes = R.drawable.ic_user,
	)

	BaseRowType.Chapter -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)

	BaseRowType.GridButton -> BaseRowItemDisplayConfig(
		aspectRatio = if (this is GridButtonBaseRowItem && gridButton.imageRes != null) 1f else ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)
}

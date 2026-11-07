package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.albumPrimaryImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesPrimaryImage
import org.jellyfin.androidtv.util.apiclient.seriesThumbImage
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.locale
import org.jellyfin.androidtv.util.sdk.getFullName
import org.jellyfin.androidtv.util.sdk.getSubName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.SeriesStatus
import org.jellyfin.androidtv.util.usbdevices.USB_TILE_UUID
import java.time.format.DateTimeFormatter
import org.jellyfin.androidtv.constant.ImageType as BaseRowImageType

open class BaseItemDtoBaseRowItem @JvmOverloads constructor(
	item: BaseItemDto,
	preferParentThumb: Boolean = false,
	staticHeight: Boolean = false,
	selectAction: BaseRowItemSelectAction = BaseRowItemSelectAction.ShowDetails,
	val preferSeriesPoster: Boolean = false,
	private val showParentTitle: Boolean = false,
	val preferMovieThumb: Boolean = false,
	val isRecording: Boolean = false,
) : BaseRowItem(
	baseRowType = when (item.type) {
		BaseItemKind.TV_CHANNEL,
		BaseItemKind.LIVE_TV_CHANNEL -> BaseRowType.LiveTvChannel

		BaseItemKind.PROGRAM,
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.LIVE_TV_PROGRAM -> BaseRowType.LiveTvProgram

		BaseItemKind.RECORDING -> BaseRowType.LiveTvRecording
		else -> BaseRowType.BaseItem
	},
	staticHeight = staticHeight,
	preferParentThumb = preferParentThumb,
	selectAction = selectAction,
	baseItem = item,
) {
	override val showCardInfoOverlay
		get() = when {
			baseItem?.id == USB_TILE_UUID -> true
			baseItem?.type == BaseItemKind.USER_VIEW -> false
			baseItem?.type == BaseItemKind.COLLECTION_FOLDER -> false
			else -> when (baseItem?.type) {
				BaseItemKind.FOLDER,
				BaseItemKind.PHOTO_ALBUM,
				BaseItemKind.PHOTO,
				BaseItemKind.VIDEO,
				BaseItemKind.PERSON,
				BaseItemKind.PLAYLIST,
				BaseItemKind.MUSIC_ARTIST -> true

				else -> false
			}
		}

	override val itemId get() = baseItem?.id

	override val isFavorite get() = baseItem?.userData?.isFavorite == true
	override val isPlayed get() = baseItem?.userData?.played == true

	override fun getCardName(context: Context) = when {
		showParentTitle && baseItem?.type == BaseItemKind.SEASON && !baseItem.seriesName.isNullOrBlank() -> baseItem.seriesName

		baseItem?.type == BaseItemKind.EPISODE -> baseItem.seriesName?.takeIf { it.isNotBlank() } ?: baseItem.name
		baseItem?.type == BaseItemKind.SERIES -> baseItem.name
		baseItem?.type == BaseItemKind.MOVIE -> baseItem.name
		baseItem?.type == BaseItemKind.RECORDING -> baseItem.name
		baseItem?.type == BaseItemKind.MUSIC_ALBUM -> baseItem.name
		baseItem?.type == BaseItemKind.AUDIO -> when {
			!baseItem.artists.isNullOrEmpty() -> baseItem.artists?.joinToString(", ")
			!baseItem.albumArtists.isNullOrEmpty() -> baseItem.albumArtists?.joinToString(", ")
			!baseItem.albumArtist.isNullOrBlank() -> baseItem.albumArtist
			!baseItem.album.isNullOrBlank() -> baseItem.album
			else -> baseItem.getFullName(context)
		}
		else -> baseItem?.getFullName(context)
	}

	override fun getFullName(context: Context) = baseItem?.getFullName(context)
	override fun getName(context: Context) = when (baseItem?.type) {
		BaseItemKind.AUDIO -> baseItem.getFullName(context)
		else -> baseItem?.name
	}

	override fun getSummary(context: Context) = baseItem?.overview

	override fun getSubText(context: Context) = when (baseItem?.type) {
		BaseItemKind.EPISODE -> {
			val seasonStr = baseItem.parentIndexNumber?.let { "S$it" }
			val episodeStr = when {
				baseItem.indexNumber != null && baseItem.indexNumberEnd != null -> "E${baseItem.indexNumber}-${baseItem.indexNumberEnd}"
				baseItem.indexNumber != null -> "E${baseItem.indexNumber}"
				else -> null
			}
			val seCode = listOfNotNull(seasonStr, episodeStr).joinToString(":")
			val title = baseItem.name.orEmpty()
			val baseSubText = when {
				seCode.isNotEmpty() && title.isNotEmpty() -> "$seCode - $title"
				seCode.isNotEmpty() -> seCode
				title.isNotEmpty() -> title
				else -> null
			}

			if (baseItem.locationType == LocationType.VIRTUAL && baseItem.premiereDate != null) {
				val formattedDate = TimeUtils.getFriendlyDate(context, baseItem.premiereDate!!)
				if (!baseSubText.isNullOrEmpty()) {
					context.getString(R.string.lbl_name_date, baseSubText, formattedDate)
				} else {
					formattedDate
				}
			} else {
				baseSubText
			}
		}

		BaseItemKind.SEASON if showParentTitle && !baseItem.seriesName.isNullOrBlank() -> baseItem.name
		BaseItemKind.SEASON -> baseItem.getSubName(context)

		BaseItemKind.SERIES -> {
			val year = baseItem.productionYear ?: baseItem.premiereDate?.year
			val statusEnum = baseItem.status?.let(SeriesStatus::fromNameOrNull)
			val isEnded = statusEnum == SeriesStatus.ENDED || baseItem.status?.equals("Ended", ignoreCase = true) == true

			val statusText = if (isEnded) {
				null
			} else {
				when (statusEnum) {
					SeriesStatus.CONTINUING -> context.getString(R.string.series_status_continuing)
					SeriesStatus.UNRELEASED -> context.getString(R.string.unreleased)
					else -> when {
						baseItem.status?.equals("Continuing", ignoreCase = true) == true -> context.getString(R.string.series_status_continuing)
						baseItem.status?.equals("Unreleased", ignoreCase = true) == true -> context.getString(R.string.unreleased)
						else -> baseItem.status
					}
				}
			}

			when {
				year != null && !statusText.isNullOrBlank() -> "$year - $statusText"
				year != null -> year.toString()
				!statusText.isNullOrBlank() -> statusText
				else -> baseItem.getSubName(context)
			}
		}

		BaseItemKind.MOVIE -> {
			val year = baseItem.productionYear ?: baseItem.premiereDate?.year ?: baseItem.startDate?.year
			year?.toString() ?: baseItem.getSubName(context)
		}

		BaseItemKind.RECORDING -> {
			val channelName = baseItem.channelName
			val episodeTitle = baseItem.episodeTitle
			val title = when {
				!channelName.isNullOrEmpty() && !episodeTitle.isNullOrEmpty() -> "$channelName - $episodeTitle"
				!channelName.isNullOrEmpty() -> channelName
				!episodeTitle.isNullOrEmpty() -> episodeTitle
				else -> ""
			}

			val startDate = baseItem.startDate
			val endDate = baseItem.endDate

			if (startDate != null && endDate != null) {
				val timeFormatter = context.getTimeFormatter()
				val formattedDate = dayMonthFormatter.withLocale(context.locale).format(startDate)
				val formattedStartTime = timeFormatter.format(startDate)
				val formattedEndTime = timeFormatter.format(endDate)

				if (title.isEmpty()) {
					"$formattedDate $formattedStartTime - $formattedEndTime"
				} else {
					"$title $formattedDate $formattedStartTime - $formattedEndTime"
				}
			} else {
				title.ifEmpty { baseItem.getSubName(context) }
			}
		}

		BaseItemKind.MUSIC_ALBUM -> {
			val artist = when {
				!baseItem.artists.isNullOrEmpty() -> baseItem.artists?.joinToString(", ")
				!baseItem.albumArtists.isNullOrEmpty() -> baseItem.albumArtists?.joinToString(", ")
				!baseItem.albumArtist.isNullOrBlank() -> baseItem.albumArtist
				else -> null
			}
			artist ?: baseItem.getSubName(context)
		}

		BaseItemKind.TV_CHANNEL -> baseItem.number
		BaseItemKind.TV_PROGRAM,
		BaseItemKind.PROGRAM -> baseItem.episodeTitle ?: baseItem.channelName

		else -> baseItem?.getSubName(context)
	}

	val isRecordingItem: Boolean
		get() = isRecording ||
			baseItem?.type == BaseItemKind.RECORDING ||
			baseRowType == BaseRowType.LiveTvRecording ||
			baseItem?.channelId != null ||
			baseItem?.timerId != null ||
			baseItem?.seriesTimerId != null

	override fun getImage(imageType: BaseRowImageType): JellyfinImage? {
		val movieThumb = if (preferMovieThumb && (baseItem?.type == BaseItemKind.MOVIE || baseItem?.type == BaseItemKind.VIDEO)) {
			baseItem?.itemImages?.get(ImageType.THUMB)
				?: baseItem?.itemImages?.get(ImageType.BACKDROP)
				?: baseItem?.itemBackdropImages?.firstOrNull()
		} else null

		val recordingThumb = if (isRecordingItem) {
			baseItem?.itemImages?.get(ImageType.THUMB)
				?: baseItem?.parentImages?.get(ImageType.THUMB)
				?: baseItem?.seriesThumbImage
				?: baseItem?.itemImages?.get(ImageType.BACKDROP)
				?: baseItem?.itemBackdropImages?.firstOrNull()
				?: baseItem?.parentBackdropImages?.firstOrNull()
				?: baseItem?.itemImages?.get(ImageType.PRIMARY)
		} else null

		val primaryImage = when {
			preferSeriesPoster && baseItem?.type == BaseItemKind.EPISODE && !isRecordingItem -> baseItem?.parentImages?.get(ImageType.PRIMARY)
				?: baseItem?.seriesPrimaryImage

			preferParentThumb && baseItem?.type == BaseItemKind.EPISODE -> baseItem?.parentImages?.get(ImageType.THUMB)
				?: baseItem?.seriesThumbImage

			(baseItem?.type == BaseItemKind.MOVIE || baseItem?.type == BaseItemKind.VIDEO) && movieThumb != null -> movieThumb
			isRecordingItem && recordingThumb != null -> recordingThumb

			baseItem?.type == BaseItemKind.SEASON -> baseItem?.itemImages?.get(ImageType.PRIMARY)
				?: baseItem?.seriesPrimaryImage

			baseItem?.type == BaseItemKind.PROGRAM -> baseItem?.itemImages?.get(ImageType.THUMB)
			baseItem?.type == BaseItemKind.AUDIO -> baseItem?.itemImages?.get(ImageType.PRIMARY) ?: baseItem?.albumPrimaryImage ?: baseItem?.parentImages?.get(ImageType.PRIMARY)
			else -> null
		} ?: baseItem?.itemImages?.get(ImageType.PRIMARY) ?: baseItem?.albumPrimaryImage ?: baseItem?.parentImages?.get(ImageType.PRIMARY)

		return when (imageType) {
			BaseRowImageType.BANNER -> baseItem?.itemImages?.get(ImageType.BANNER) ?: primaryImage
			BaseRowImageType.THUMB -> baseItem?.itemImages?.get(ImageType.THUMB) ?: movieThumb ?: recordingThumb ?: primaryImage
			else -> primaryImage
		}
	}

	override fun equals(other: Any?): Boolean {
		if (other is BaseItemDtoBaseRowItem) return other.baseItem == baseItem
		return super.equals(other)
	}

	private companion object {
		private val dayMonthFormatter: DateTimeFormatter by lazy {
			DateTimeFormatter.ofPattern("d MMM")
		}
	}
}

package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest

class GenericFolderFragment : EnhancedBrowseFragment() {
	companion object {
		private val showSpecialViewTypes = setOf(
			BaseItemKind.COLLECTION_FOLDER,
			BaseItemKind.FOLDER,
			BaseItemKind.USER_VIEW,
			BaseItemKind.CHANNEL_FOLDER_ITEM,
		)
	}

	override fun setupQueries(rowLoader: RowLoader) {
		val folder = mFolder ?: return

		if ((folder.childCount == null || folder.childCount == 0) && !setOf(
				BaseItemKind.CHANNEL,
				BaseItemKind.CHANNEL_FOLDER_ITEM,
				BaseItemKind.USER_VIEW,
				BaseItemKind.COLLECTION_FOLDER,
			).contains(folder.type)
		) return

		if (showSpecialViewTypes.contains(folder.type)) {
			if (folder.type != BaseItemKind.CHANNEL_FOLDER_ITEM) {
				val resume = GetItemsRequest(
					fields = ItemRepository.browseFields,
					parentId = folder.id,
					limit = 50,
					isMissing = false,
					isUnaired = false,
					excludeLocationTypes = setOf(LocationType.VIRTUAL),
					filters = setOf(ItemFilter.IS_RESUMABLE),
					sortBy = setOf(ItemSortBy.DATE_PLAYED),
					sortOrder = setOf(SortOrder.DESCENDING),
				)
				mRows.add(BrowseRowDef(getString(R.string.lbl_continue_watching), resume, 0))
			}

			val latest = GetItemsRequest(
				fields = ItemRepository.browseFields,
				parentId = folder.id,
				limit = 50,
				isMissing = false,
				isUnaired = false,
				excludeLocationTypes = setOf(LocationType.VIRTUAL),
				filters = setOf(ItemFilter.IS_UNPLAYED),
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
			)
			mRows.add(BrowseRowDef(getString(R.string.lbl_latest), latest, 0))
		}

		val byName = GetItemsRequest(
			fields = ItemRepository.browseFields,
			parentId = folder.id,
		)
		val header = when (folder.type) {
			BaseItemKind.SEASON -> folder.name
			else -> getString(R.string.lbl_by_name)
		}

		mRows.add(BrowseRowDef(header ?: "", byName, 100))

		if (folder.type == BaseItemKind.SEASON) {
			val specials = GetSpecialsRequest(folder.id)
			mRows.add(BrowseRowDef(getString(R.string.lbl_specials), specials))

			val seriesId = folder.seriesId ?: folder.parentId
			if (seriesId != null) {
				val otherSeasons = GetItemsRequest(
					fields = ItemRepository.browseFields,
					parentId = seriesId,
					includeItemTypes = setOf(BaseItemKind.SEASON),
					excludeItemIds = setOf(folder.id),
				)
				mRows.add(BrowseRowDef(getString(R.string.lbl_seasons), otherSeasons, 100))

				val series = GetItemsRequest(
					fields = ItemRepository.browseFields,
					ids = setOf(seriesId),
				)
				mRows.add(BrowseRowDef(getString(R.string.lbl_series), series, 100))
			}
		}

		rowLoader.loadRows(mRows)
	}
}

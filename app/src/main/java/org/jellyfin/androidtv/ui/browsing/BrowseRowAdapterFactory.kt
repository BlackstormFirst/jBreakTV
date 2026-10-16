package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter

object BrowseRowAdapterFactory {
	fun createAdapter(context: Context, def: BrowseRowDef, presenter: CardPresenter = CardPresenter()): ItemRowAdapter {
		val type = def.queryType ?: QueryType.Items
		return when {
			def.items != null -> ItemRowAdapter(context, def.items, presenter)
			def.nextUpQuery != null -> ItemRowAdapter(context, def.nextUpQuery, def.preferParentThumb, presenter, null)
			def.latestItemsQuery != null -> ItemRowAdapter(context, def.latestItemsQuery, def.preferParentThumb, presenter, null)
			def.resumeQuery != null -> ItemRowAdapter(context, def.resumeQuery, def.chunkSize, def.preferParentThumb, def.isStaticHeight, presenter, null)
			def.similarQuery != null -> ItemRowAdapter(context, def.similarQuery, type, presenter, null)
			def.artistsQuery != null -> ItemRowAdapter(context, def.artistsQuery, def.chunkSize, presenter, null)
			def.albumArtistsQuery != null -> ItemRowAdapter(context, def.albumArtistsQuery, def.chunkSize, presenter, null)
			def.seriesTimerQuery != null -> ItemRowAdapter(context, def.seriesTimerQuery, presenter, null)
			def.tvChannelQuery != null -> ItemRowAdapter(context, def.tvChannelQuery, def.chunkSize, presenter, null)
			def.programQuery != null -> ItemRowAdapter(context, def.programQuery, presenter, null)
			def.recordingQuery != null -> ItemRowAdapter(context, def.recordingQuery, def.chunkSize, presenter, null)
			def.specialsQuery != null -> ItemRowAdapter(context, def.specialsQuery, presenter, null)
			else -> ItemRowAdapter(
				context,
				def.query,
				def.chunkSize,
				def.preferParentThumb,
				def.isStaticHeight,
				presenter,
				null,
				type
			)
		}
	}
}

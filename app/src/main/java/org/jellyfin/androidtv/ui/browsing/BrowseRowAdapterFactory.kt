package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter

object BrowseRowAdapterFactory {
	fun createAdapter(context: Context, def: BrowseRowDef, presenter: CardPresenter = CardPresenter()): ItemRowAdapter {
		val type = def.queryType ?: QueryType.Items
		val items = def.items
		val nextUpQuery = def.nextUpQuery
		val latestItemsQuery = def.latestItemsQuery
		val resumeQuery = def.resumeQuery
		val similarQuery = def.similarQuery
		val artistsQuery = def.artistsQuery
		val albumArtistsQuery = def.albumArtistsQuery
		val seriesTimerQuery = def.seriesTimerQuery
		val tvChannelQuery = def.tvChannelQuery
		val programQuery = def.programQuery
		val recordingQuery = def.recordingQuery
		val specialsQuery = def.specialsQuery
		val query = def.query

		return when {
			items != null -> ItemRowAdapter(context, items, presenter)
			nextUpQuery != null -> ItemRowAdapter(context, nextUpQuery, def.preferParentThumb, presenter, null)
			latestItemsQuery != null -> ItemRowAdapter(context, latestItemsQuery, def.preferParentThumb, presenter, null)
			resumeQuery != null -> ItemRowAdapter(context, resumeQuery, def.chunkSize, def.preferParentThumb, def.isStaticHeight, presenter, null)
			similarQuery != null -> ItemRowAdapter(context, similarQuery, type, presenter, null)
			artistsQuery != null -> ItemRowAdapter(context, artistsQuery, def.chunkSize, presenter, null)
			albumArtistsQuery != null -> ItemRowAdapter(context, albumArtistsQuery, def.chunkSize, presenter, null)
			seriesTimerQuery != null -> ItemRowAdapter(context, seriesTimerQuery, presenter, null)
			tvChannelQuery != null -> ItemRowAdapter(context, tvChannelQuery, def.chunkSize, presenter, null)
			programQuery != null -> ItemRowAdapter(context, programQuery, presenter, null)
			recordingQuery != null -> ItemRowAdapter(context, recordingQuery, def.chunkSize, presenter, null)
			specialsQuery != null -> ItemRowAdapter(context, specialsQuery, presenter, null)
			query != null -> ItemRowAdapter(
				context,
				query,
				def.chunkSize,
				def.preferParentThumb,
				def.isStaticHeight,
				presenter,
				null,
				type
			)
			else -> ItemRowAdapter(presenter)
		}
	}
}

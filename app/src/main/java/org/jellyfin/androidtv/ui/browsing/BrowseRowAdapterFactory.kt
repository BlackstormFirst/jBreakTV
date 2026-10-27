package org.jellyfin.androidtv.ui.browsing

import android.content.Context
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter

object BrowseRowAdapterFactory {
	fun createAdapter(context: Context, def: BrowseRowDef): ItemRowAdapter {
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
			items != null -> ItemRowAdapter(context, items)
			nextUpQuery != null -> ItemRowAdapter(context, nextUpQuery, def.preferParentThumb, null, null)
			latestItemsQuery != null -> ItemRowAdapter(context, latestItemsQuery, def.preferParentThumb, null, null)
			resumeQuery != null -> ItemRowAdapter(context, resumeQuery, def.chunkSize, def.preferParentThumb, def.isStaticHeight, null, null)
			similarQuery != null -> ItemRowAdapter(context, similarQuery, type, null, null)
			artistsQuery != null -> ItemRowAdapter(context, artistsQuery, def.chunkSize, null, null)
			albumArtistsQuery != null -> ItemRowAdapter(context, albumArtistsQuery, def.chunkSize, null, null)
			seriesTimerQuery != null -> ItemRowAdapter(context, seriesTimerQuery, null, null)
			tvChannelQuery != null -> ItemRowAdapter(context, tvChannelQuery, def.chunkSize, null, null)
			programQuery != null -> ItemRowAdapter(context, programQuery, null, null)
			recordingQuery != null -> ItemRowAdapter(context, recordingQuery, def.chunkSize, null, null)
			specialsQuery != null -> ItemRowAdapter(context, specialsQuery, null, null)
			query != null -> ItemRowAdapter(
				context,
				query,
				def.chunkSize,
				def.preferParentThumb,
				def.isStaticHeight,
				null,
				null,
				type
			)
			else -> ItemRowAdapter()
		}
	}
}

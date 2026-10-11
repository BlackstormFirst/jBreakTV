package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

interface UserViewsRepository {
	val views: Flow<Collection<BaseItemDto>>

	fun isSupported(collectionType: CollectionType?): Boolean
	fun allowViewSelection(collectionType: CollectionType?): Boolean
	fun allowGridView(collectionType: CollectionType?): Boolean
	fun clearCache()
	suspend fun getCachedOrFetchedViews(): Collection<BaseItemDto>
}

class UserViewsRepositoryImpl(
	private val api: ApiClient,
) : UserViewsRepository {
	private var cachedViews: List<BaseItemDto>? = null
	private val mutex = Mutex()

	override val views = flow {
		emit(getCachedOrFetchedViews())
	}.flowOn(Dispatchers.IO)

	override suspend fun getCachedOrFetchedViews(): Collection<BaseItemDto> {
		return mutex.withLock {
			cachedViews ?: run {
				val viewsResponse by api.userViewsApi.getUserViews()
				val filteredViews = viewsResponse.items
					.filter { isSupported(it.collectionType) }
				cachedViews = filteredViews
				filteredViews
			}
		}
	}

	override fun clearCache() {
		cachedViews = null
	}

	override fun isSupported(collectionType: CollectionType?) = collectionType !in unsupportedCollectionTypes
	override fun allowViewSelection(collectionType: CollectionType?) = collectionType !in disallowViewSelectionCollectionTypes
	override fun allowGridView(collectionType: CollectionType?) = collectionType !in disallowGridViewCollectionTypes

	private companion object {
		private val unsupportedCollectionTypes = arrayOf(
			CollectionType.BOOKS,
			CollectionType.FOLDERS
		)

		private val disallowViewSelectionCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC,
			CollectionType.PHOTOS,
		)

		private val disallowGridViewCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC
		)
	}
}

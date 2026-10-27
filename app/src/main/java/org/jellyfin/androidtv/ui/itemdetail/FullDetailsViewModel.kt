package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.querying.GetAdditionalPartsRequest
import org.jellyfin.androidtv.data.querying.GetNextEpisodesRequest
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.querying.GetTrailersRequest
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.browsing.BrowseRowAdapterFactory
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.browsing.BrowsingUtils
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.androidtv.util.sdk.buildChapterItems
import org.jellyfin.androidtv.util.sdk.compat.canResume
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class DetailsRowState(
    val title: String,
    val adapter: ItemRowAdapter
)

data class FullDetailsUiState(
    val isLoading: Boolean = true,
    val item: BaseItemDto? = null,
    val selectedMediaSource: MediaSourceInfo? = null,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
    val canResume: Boolean = false,
    val resumePositionText: String? = null,
    val endTimeText: String? = null,
    val directorText: String? = null,
    val writerText: String? = null,
    val genresText: String? = null,
    val canDelete: Boolean = false,
    val prevItemId: UUID? = null,
    val rows: List<DetailsRowState> = emptyList()
)

class FullDetailsViewModel(
    private val api: ApiClient,
    private val userRepository: UserRepository,
    private val itemMutationRepository: ItemMutationRepository,
    private val dataRefreshService: DataRefreshService
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullDetailsUiState())
    val uiState: StateFlow<FullDetailsUiState> = _uiState.asStateFlow()

    private var currentItemId: UUID? = null
    private var justLoaded = true
    private var appContext: Context? = null
    private var fetchJob: Job? = null

    suspend fun subscribeWebSockets() = kotlinx.coroutines.coroutineScope {
        launch {
            api.webSocket.subscribe<UserDataChangedMessage>()
                .collect { message ->
                    val changedData = message.data
                    val itemId = currentItemId
                    if (itemId != null && changedData?.userDataList?.any { it.itemId == itemId } == true) {
                        dataRefreshService.lastPlayback = Instant.now()
                        refresh()
                    }
                }
        }
        launch {
            api.webSocket.subscribe<LibraryChangedMessage>()
                .collect {
                    dataRefreshService.lastLibraryChange = Instant.now()
                    refresh()
                }
        }
    }

    fun loadItem(context: Context, itemId: UUID) {
        appContext = context.applicationContext
        currentItemId = itemId
        justLoaded = true
        fetchItem(context, itemId, showLoading = true)
    }

    fun onResume(context: Context, itemId: UUID) {
        appContext = context.applicationContext
        if (!justLoaded) {
            refresh(context)
        } else {
            justLoaded = false
        }
    }

    fun refresh(context: Context? = appContext) {
        val ctx = context ?: return
        val itemId = currentItemId ?: return
        fetchItem(ctx, itemId, showLoading = false)
    }

    private fun fetchItem(context: Context, itemId: UUID, showLoading: Boolean) {
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }

            val currentUser = userRepository.currentUser.filterNotNull().first()
            val userId = currentUser.id

            val baseItem = withContext(Dispatchers.IO) {
                runCatching {
                    api.userLibraryApi.getItem(userId = userId, itemId = itemId).content
                }.getOrElse {
                    runCatching {
                        val response = api.itemsApi.getItems(
                            userId = userId,
                            ids = listOf(itemId),
                            fields = setOf(
                                ItemFields.CAN_DELETE,
                                ItemFields.CHAPTERS,
                                ItemFields.CHILD_COUNT,
                                ItemFields.CUMULATIVE_RUN_TIME_TICKS,
                                ItemFields.DATE_CREATED,
                                ItemFields.GENRES,
                                ItemFields.ITEM_COUNTS,
                                ItemFields.MEDIA_SOURCES,
                                ItemFields.MEDIA_STREAMS,
                                ItemFields.OVERVIEW,
                                ItemFields.PEOPLE,
                                ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                                ItemFields.TAGLINES
                            )
                        ).content
                        response.items.firstOrNull()
                    }.getOrNull()
                }
            }

            if (baseItem == null) {
                if (showLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                return@launch
            }

            val isFav = baseItem.userData?.isFavorite ?: false
            val isPl = baseItem.userData?.played ?: false
            val canRes = baseItem.canResume

            val resumeText = if (canRes) {
                val ticks = baseItem.userData?.playbackPositionTicks ?: 0L
                val millis = ticks / 10000
                TimeUtils.formatMillis(millis)
            } else null

            val endTimeText = calculateEndTime(context, baseItem)

            val directors = baseItem.people?.filter { it.type == PersonKind.DIRECTOR }?.mapNotNull { it.name }?.joinToString(", ")
            val writers = baseItem.people?.filter { it.type == PersonKind.WRITER }?.mapNotNull { it.name }?.joinToString(", ")
            val genres = baseItem.genres?.joinToString(" / ")

            val canDel = currentUser.policy?.enableContentDeletion == true && baseItem.canDelete == true

            // Fetch previous episode ID for EPISODE (Leanback mPrevItemId)
            val previousEpisodeId = if (baseItem.type == BaseItemKind.EPISODE && baseItem.seriesId != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val response = api.tvShowsApi.getEpisodes(
                            seriesId = baseItem.seriesId!!,
                            adjacentTo = baseItem.id
                        ).content
                        val currentIndex = response.items.indexOfFirst { it.id == baseItem.id }
                        if (currentIndex > 0) response.items.getOrNull(currentIndex - 1)?.id else null
                    }.getOrNull()
                }
            } else null

            val mediaSources = baseItem.mediaSources?.takeIf { sources -> sources.any { !it.mediaStreams.isNullOrEmpty() } }
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        api.mediaInfoApi.getPlaybackInfo(
                            itemId = baseItem.id,
                            userId = userId
                        ).content.mediaSources
                    }.getOrElse {
                        runCatching {
                            api.mediaInfoApi.getPostedPlaybackInfo(
                                itemId = baseItem.id,
                                data = PlaybackInfoDto(
                                    userId = userId,
                                    enableDirectPlay = true,
                                    enableDirectStream = true,
                                    enableTranscoding = true
                                )
                            ).content.mediaSources
                        }.getOrNull()
                    }
                }

            val updatedBaseItem = if (!mediaSources.isNullOrEmpty()) {
                baseItem.copy(mediaSources = mediaSources)
            } else baseItem

            val currentSelectedSource = _uiState.value.selectedMediaSource
            val selectedSource = updatedBaseItem.mediaSources?.firstOrNull { it.id == currentSelectedSource?.id }
                ?: updatedBaseItem.mediaSources?.firstOrNull()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                item = updatedBaseItem,
                selectedMediaSource = selectedSource,
                isFavorite = isFav,
                isPlayed = isPl,
                canResume = canRes,
                resumePositionText = resumeText,
                endTimeText = endTimeText,
                directorText = directors,
                writerText = writers,
                genresText = genres,
                canDelete = canDel,
                prevItemId = previousEpisodeId
            )

            loadAdditionalRows(context, updatedBaseItem, userId)
        }
    }

    fun selectMediaSource(context: Context, mediaSource: MediaSourceInfo) {
        _uiState.value = _uiState.value.copy(selectedMediaSource = mediaSource)
        val mediaSourceId = mediaSource.id?.let { Utils.uuidOrNull(it) } ?: return
        if (mediaSourceId != currentItemId) {
            currentItemId = mediaSourceId
            fetchItem(context, mediaSourceId, showLoading = false)
        }
    }

    private fun calculateEndTime(context: Context, baseItem: BaseItemDto): String? {
        val runtimeTicks = baseItem.runTimeTicks ?: return null
        if (runtimeTicks <= 0) return null

        val currentPos = baseItem.userData?.playbackPositionTicks ?: 0L
        val remainingTicks = (runtimeTicks - currentPos).coerceAtLeast(0L)
        val endTime = LocalDateTime.now().plusNanos(remainingTicks * 100)
        return context.getTimeFormatter().format(endTime)
    }

    private fun loadAdditionalRows(context: Context, baseItem: BaseItemDto, userId: UUID) {
        viewModelScope.launch {
            val rows = mutableListOf<DetailsRowState>()

            when (baseItem.type) {
                BaseItemKind.MOVIE -> {
                    // Additional Parts
                    if ((baseItem.partCount ?: 0) > 0) {
                        runCatching {
                            val query = GetAdditionalPartsRequest(baseItem.id)
                            val adapter = ItemRowAdapter(context, query, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_additional_parts), adapter))
                        }
                    }

                    // Cast / Crew
                    baseItem.people?.takeIf { it.isNotEmpty() }?.let { peopleList ->
                        runCatching {
                            val adapter = ItemRowAdapter(peopleList, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_cast_crew), adapter))
                        }
                    }

                    // Chapters
                    baseItem.chapters?.takeIf { it.isNotEmpty() }?.let {
                        runCatching {
                            val chapterItems = baseItem.buildChapterItems()
                            val adapter = ItemRowAdapter(context, chapterItems, CardPresenter(true, 120), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_chapters), adapter))
                        }
                    }

                    // Specials
                    if ((baseItem.specialFeatureCount ?: 0) > 0) {
                        runCatching {
                            val query = GetSpecialsRequest(baseItem.id)
                            val adapter = ItemRowAdapter(context, query, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_specials), adapter))
                        }
                    }

                    // Trailers
                    if ((baseItem.localTrailerCount ?: 0) > 1) {
                        runCatching {
                            val query = GetTrailersRequest(baseItem.id)
                            val adapter = ItemRowAdapter(context, query, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_trailers), adapter))
                        }
                    }

                    // Similar Movies
                    runCatching {
                        val similarQuery = GetSimilarItemsRequest(itemId = baseItem.id, userId = userId, limit = 12, fields = ItemRepository.browseFields)
                        val similarDef = BrowseRowDef(context.getString(R.string.lbl_more_like_this), similarQuery, QueryType.SimilarMovies)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, similarDef)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_more_like_this), adapter))
                    }
                }

                BaseItemKind.TRAILER -> {
                    // Cast / Crew
                    baseItem.people?.takeIf { it.isNotEmpty() }?.let { peopleList ->
                        runCatching {
                            val adapter = ItemRowAdapter(peopleList, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_cast_crew), adapter))
                        }
                    }

                    // Similar Trailers
                    runCatching {
                        val similarQuery = GetSimilarItemsRequest(itemId = baseItem.id, userId = userId, limit = 12, fields = ItemRepository.browseFields)
                        val similarDef = BrowseRowDef(context.getString(R.string.lbl_more_like_this), similarQuery, QueryType.SimilarMovies)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, similarDef)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_more_like_this), adapter))
                    }
                }

                BaseItemKind.SERIES -> {
                    // Next Up
                    runCatching {
                        val nextUpQuery = GetNextUpRequest(seriesId = baseItem.id, userId = userId, fields = ItemRepository.nextUpFields)
                        val adapter = ItemRowAdapter(context, nextUpQuery, false, CardPresenter(true, 130), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_next_up), adapter))
                    }

                    // Seasons
                    runCatching {
                        val seasonsQuery = GetSeasonsRequest(seriesId = baseItem.id, userId = userId, fields = ItemRepository.browseFields)
                        val adapter = ItemRowAdapter(context, seasonsQuery, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_seasons), adapter))
                    }

                    // Upcoming Episodes
                    runCatching {
                        val upcomingQuery = GetUpcomingEpisodesRequest(parentId = baseItem.id, userId = userId, fields = ItemRepository.browseFields)
                        val adapter = ItemRowAdapter(context, upcomingQuery, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_upcoming), adapter))
                    }

                    // Specials
                    if ((baseItem.specialFeatureCount ?: 0) > 0) {
                        runCatching {
                            val query = GetSpecialsRequest(baseItem.id)
                            val adapter = ItemRowAdapter(context, query, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_specials), adapter))
                        }
                    }

                    // Cast / Crew
                    baseItem.people?.takeIf { it.isNotEmpty() }?.let { peopleList ->
                        runCatching {
                            val adapter = ItemRowAdapter(peopleList, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_cast_crew), adapter))
                        }
                    }

                    // Similar Series
                    runCatching {
                        val similarQuery = GetSimilarItemsRequest(itemId = baseItem.id, userId = userId, limit = 12, fields = ItemRepository.browseFields)
                        val similarDef = BrowseRowDef(context.getString(R.string.lbl_more_like_this), similarQuery, QueryType.SimilarSeries)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, similarDef)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_more_like_this), adapter))
                    }
                }

                BaseItemKind.SEASON -> {
                    // Episodes of this Season
                    runCatching {
                        val query = GetItemsRequest(parentId = baseItem.id, userId = userId, fields = ItemRepository.browseFields)
                        val def = BrowseRowDef(context.getString(R.string.lbl_episodes), query, 0)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, def)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_episodes), adapter))
                    }

                    // Seasons of parent Series (if seriesId present)
                    baseItem.seriesId?.let { seriesId ->
                        runCatching {
                            val seasonsQuery = GetSeasonsRequest(seriesId = seriesId, userId = userId, fields = ItemRepository.browseFields)
                            val adapter = ItemRowAdapter(context, seasonsQuery, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_seasons), adapter))
                        }
                    }

                    // Cast / Crew
                    baseItem.people?.takeIf { it.isNotEmpty() }?.let { peopleList ->
                        runCatching {
                            val adapter = ItemRowAdapter(peopleList, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_cast_crew), adapter))
                        }
                    }
                }

                BaseItemKind.EPISODE -> {
                    // Additional Parts
                    if ((baseItem.partCount ?: 0) > 0) {
                        runCatching {
                            val query = GetAdditionalPartsRequest(baseItem.id)
                            val adapter = ItemRowAdapter(context, query, CardPresenter(), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_additional_parts), adapter))
                        }
                    }

                    // Next Episode
                    if (baseItem.seasonId != null && baseItem.seriesId != null) {
                        runCatching {
                            val query = GetNextEpisodesRequest(baseItem.seriesId!!, baseItem.seasonId!!, baseItem.id)
                            val adapter = ItemRowAdapter(context, query, true, CardPresenter(true, 120), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_next_episode), adapter))
                        }
                    }

                    // Guest Stars (matching Leanback: only PersonKind.GUEST_STAR)
                    baseItem.people?.filter { it.type == PersonKind.GUEST_STAR }?.takeIf { it.isNotEmpty() }?.let { guests ->
                        runCatching {
                            val adapter = ItemRowAdapter(guests, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_guest_stars), adapter))
                        }
                    }

                    // Chapters
                    baseItem.chapters?.takeIf { it.isNotEmpty() }?.let {
                        runCatching {
                            val chapterItems = baseItem.buildChapterItems()
                            val adapter = ItemRowAdapter(context, chapterItems, CardPresenter(true, 120), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_chapters), adapter))
                        }
                    }
                }

                BaseItemKind.PERSON -> {
                    // Movies (using BrowsingUtils.createPersonItemsRequest for recursive search)
                    runCatching {
                        val query = BrowsingUtils.createPersonItemsRequest(baseItem.id, BaseItemKind.MOVIE)
                        val adapter = ItemRowAdapter(context, query, 100, false, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_movies), adapter))
                    }

                    // TV Series
                    runCatching {
                        val query = BrowsingUtils.createPersonItemsRequest(baseItem.id, BaseItemKind.SERIES)
                        val adapter = ItemRowAdapter(context, query, 100, false, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_tv_series), adapter))
                    }

                    // Episodes
                    runCatching {
                        val query = BrowsingUtils.createPersonItemsRequest(baseItem.id, BaseItemKind.EPISODE)
                        val adapter = ItemRowAdapter(context, query, 100, false, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_episodes), adapter))
                    }
                }

                BaseItemKind.MUSIC_ARTIST -> {
                    // Albums
                    runCatching {
                        val query = BrowsingUtils.createArtistItemsRequest(baseItem.id, BaseItemKind.MUSIC_ALBUM)
                        val adapter = ItemRowAdapter(context, query, 100, false, CardPresenter(), null)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_albums), adapter))
                    }
                }

                BaseItemKind.MUSIC_ALBUM -> {
                    // Songs
                    runCatching {
                        val query = GetItemsRequest(parentId = baseItem.id, includeItemTypes = listOf(BaseItemKind.AUDIO), userId = userId, fields = ItemRepository.browseFields)
                        val def = BrowseRowDef(context.getString(R.string.lbl_songs), query, 100)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, def)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_songs), adapter))
                    }

                    // Cast / Crew
                    baseItem.people?.takeIf { it.isNotEmpty() }?.let { peopleList ->
                        runCatching {
                            val adapter = ItemRowAdapter(peopleList, context, CardPresenter(true, 130), null)
                            adapter.Retrieve()
                            rows.add(DetailsRowState(context.getString(R.string.lbl_cast_crew), adapter))
                        }
                    }
                }

                BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST -> {
                    // Items in Collection / Playlist
                    runCatching {
                        val query = GetItemsRequest(parentId = baseItem.id, userId = userId, fields = ItemRepository.browseFields)
                        val def = BrowseRowDef(context.getString(R.string.lbl_items), query, 100)
                        val adapter = BrowseRowAdapterFactory.createAdapter(context, def)
                        adapter.Retrieve()
                        rows.add(DetailsRowState(context.getString(R.string.lbl_items), adapter))
                    }
                }

                else -> Unit
            }

            _uiState.value = _uiState.value.copy(rows = rows)
        }
    }

    fun toggleFavorite() {
        val currentItem = _uiState.value.item ?: return
        val newFav = !_uiState.value.isFavorite
        _uiState.value = _uiState.value.copy(isFavorite = newFav)
        viewModelScope.launch {
            runCatching {
                itemMutationRepository.setFavorite(currentItem.id, newFav)
                dataRefreshService.lastFavoriteUpdate = Instant.now()
            }.onFailure {
                _uiState.value = _uiState.value.copy(isFavorite = !newFav)
            }
        }
    }

    fun togglePlayed() {
        val currentItem = _uiState.value.item ?: return
        val newPlayed = !_uiState.value.isPlayed
        _uiState.value = _uiState.value.copy(
            isPlayed = newPlayed,
            canResume = if (newPlayed) false else _uiState.value.canResume
        )
        viewModelScope.launch {
            runCatching {
                itemMutationRepository.setPlayed(currentItem.id, newPlayed)
                dataRefreshService.lastPlayback = Instant.now()
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isPlayed = !newPlayed,
                    canResume = if (!newPlayed) false else _uiState.value.canResume
                )
            }
        }
    }

    fun deleteCurrentItem(onSuccess: () -> Unit) {
        val currentItem = _uiState.value.item ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.libraryApi.deleteItem(currentItem.id)
                }
                dataRefreshService.lastDeletedItemId = currentItem.id
                onSuccess()
            }
        }
    }
}

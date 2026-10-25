package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.ChapterItemInfo
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.data.querying.GetAdditionalPartsRequest
import org.jellyfin.androidtv.data.querying.GetNextEpisodesRequest
import org.jellyfin.androidtv.data.querying.GetSeriesTimersRequest
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.querying.GetTrailersRequest
import org.jellyfin.androidtv.data.querying.GetUserViewsRequest
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.androidtv.ui.browsing.EnhancedBrowseFragment
import org.jellyfin.androidtv.ui.browsing.SortOption
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.TextItemPresenter
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetAlbumArtistsRequest
import org.jellyfin.sdk.model.api.request.GetArtistsRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetLiveTvChannelsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.jellyfin.sdk.model.api.request.GetSeasonsRequest
import org.jellyfin.sdk.model.api.request.GetSimilarItemsRequest
import org.jellyfin.sdk.model.api.request.GetUpcomingEpisodesRequest
import org.koin.java.KoinJavaComponent.get
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.time.Instant

@Suppress("TooManyFunctions", "LargeClass", "ComplexMethod", "LongMethod")
class ItemRowAdapter : MutableObjectAdapter<Any> {
    var query: GetItemsRequest? = null
    var nextUpQuery: GetNextUpRequest? = null
    var seasonQuery: GetSeasonsRequest? = null
    var upcomingQuery: GetUpcomingEpisodesRequest? = null
    var similarQuery: GetSimilarItemsRequest? = null
    var specialsQuery: GetSpecialsRequest? = null
    var additionalPartsQuery: GetAdditionalPartsRequest? = null
    var trailersQuery: GetTrailersRequest? = null
    var tvChannelQuery: GetLiveTvChannelsRequest? = null
    var tvProgramQuery: GetRecommendedProgramsRequest? = null
    var tvRecordingQuery: GetRecordingsRequest? = null
    var artistsQuery: GetArtistsRequest? = null
    var albumArtistsQuery: GetAlbumArtistsRequest? = null
    var latestQuery: GetLatestMediaRequest? = null
    var resumeQuery: GetResumeItemsRequest? = null
    var nextEpisodesQuery: GetNextEpisodesRequest? = null
    var seriesTimerQuery: GetSeriesTimersRequest? = null
    var queryType: QueryType? = null

    var sortBy: ItemSortBy? = null
    var sortOrder: SortOrder? = null
    var filters: FilterOptions? = null

    private var retrieveFinishedListener: (() -> Unit)? = null

    var reRetrieveTriggers: Array<ChangeTriggerType>? = null
    var lastFullRetrieve: Instant? = null

    var persons: Array<BaseItemPerson>? = null
    var chapters: List<ChapterItemInfo>? = null
    var initialItems: List<BaseItemDto>? = null
    var parent: MutableObjectAdapter<Row>? = null
    var row: ListRow? = null
    var siblingRow: Row? = null
    var chunkSize: Int = 0

    var itemsLoaded: Int = 0
        set(value) {
            field = value
            fullyLoaded = chunkSize == 0 || value >= totalItems
        }

    var totalItems: Int = 0
    var fullyLoaded: Boolean = false

    private val currentlyRetrievingSemaphore = Any()
    var isCurrentlyRetrieving: Boolean = false
        private set

    var preferParentThumb: Boolean = false
    var isStaticHeight: Boolean = false

    private val api: Lazy<ApiClient> = inject(ApiClient::class.java)
    private val userViewsRepository: Lazy<UserViewsRepository> = inject(UserViewsRepository::class.java)
    var context: Context? = null

    constructor(context: Context, query: GetItemsRequest, chunkSize: Int, preferParentThumb: Boolean, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : this(context, query, chunkSize, preferParentThumb, false, presenter, parent, QueryType.Items)

    constructor(context: Context, query: GetItemsRequest, chunkSize: Int, preferParentThumb: Boolean, staticHeight: Boolean, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null, queryType: QueryType = QueryType.Items) : super(presenter) {
        this.context = context
        this.parent = parent
        this.query = query
        this.chunkSize = chunkSize
        this.preferParentThumb = preferParentThumb
        this.isStaticHeight = staticHeight
        this.queryType = queryType
    }

    constructor(context: Context, query: GetItemsRequest, chunkSize: Int, preferParentThumb: Boolean, staticHeight: Boolean, presenterSelector: PresenterSelector, parent: MutableObjectAdapter<Row>? = null, queryType: QueryType = QueryType.Items) : super(presenterSelector) {
        this.context = context
        this.parent = parent
        this.query = query
        this.chunkSize = chunkSize
        this.preferParentThumb = preferParentThumb
        this.isStaticHeight = staticHeight
        this.queryType = queryType
    }

    constructor(context: Context, query: GetArtistsRequest, chunkSize: Int, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.artistsQuery = query
        this.chunkSize = chunkSize
        this.queryType = QueryType.Artists
    }

    constructor(context: Context, query: GetAlbumArtistsRequest, chunkSize: Int, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.albumArtistsQuery = query
        this.chunkSize = chunkSize
        this.queryType = QueryType.AlbumArtists
    }

    constructor(context: Context, query: GetSeriesTimersRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.seriesTimerQuery = query
        this.isStaticHeight = true
        this.queryType = QueryType.SeriesTimer
    }

    constructor(context: Context, query: GetNextUpRequest, preferParentThumb: Boolean = false, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.nextUpQuery = query
        this.queryType = QueryType.NextUp
        this.isStaticHeight = true
        this.preferParentThumb = preferParentThumb
    }

    constructor(context: Context, query: GetLatestMediaRequest, preferParentThumb: Boolean = false, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.latestQuery = query
        this.queryType = QueryType.LatestItems
        this.isStaticHeight = true
        this.preferParentThumb = preferParentThumb
    }

    constructor(context: Context, query: GetLatestMediaRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : this(context, query, false, presenter, parent)

    constructor(context: Context, persons: Array<BaseItemPerson>, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.persons = persons
        this.isStaticHeight = true
        this.queryType = QueryType.StaticPeople
    }

    constructor(persons: List<BaseItemPerson>, context: Context, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : this(context, persons.toTypedArray(), presenter, parent)

    constructor(context: Context, chapters: List<ChapterItemInfo>, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.chapters = chapters
        this.isStaticHeight = true
        this.queryType = QueryType.StaticChapters
    }

    constructor(context: Context, items: List<BaseRowItem>, presenter: Presenter) : super(presenter) {
        this.context = context
        this.queryType = QueryType.StaticItems
        items.forEach { add(it) }
    }

    constructor(context: Context, items: List<BaseItemDto>, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null, queryType: QueryType = QueryType.StaticItems) : super(presenter) {
        this.context = context
        this.parent = parent
        this.initialItems = items
        this.queryType = queryType
    }

    constructor(context: Context, items: List<BaseItemDto>, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null, staticItems: Boolean = true) : super(presenter) {
        this.context = context
        this.parent = parent
        this.initialItems = items
        this.queryType = QueryType.StaticItems
    }

    constructor(context: Context, query: GetSpecialsRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.specialsQuery = query
        this.queryType = QueryType.Specials
    }

    constructor(context: Context, query: GetAdditionalPartsRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.additionalPartsQuery = query
        this.queryType = QueryType.AdditionalParts
    }

    constructor(context: Context, query: GetTrailersRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.trailersQuery = query
        this.queryType = QueryType.Trailers
    }

    constructor(context: Context, query: GetNextEpisodesRequest, staticHeight: Boolean, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.nextEpisodesQuery = query
        this.isStaticHeight = staticHeight
        this.queryType = QueryType.NextEpisodes
    }

    constructor(context: Context, query: GetLiveTvChannelsRequest, chunkSize: Int, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.tvChannelQuery = query
        this.chunkSize = chunkSize
        this.queryType = QueryType.LiveTvChannel
    }

    constructor(context: Context, query: GetRecommendedProgramsRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.tvProgramQuery = query
        this.queryType = QueryType.LiveTvProgram
        this.isStaticHeight = true
    }

    constructor(context: Context, query: GetRecordingsRequest, chunkSize: Int, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.tvRecordingQuery = query
        this.chunkSize = chunkSize
        this.queryType = QueryType.LiveTvRecording
        this.isStaticHeight = true
    }

    constructor(context: Context, query: GetSimilarItemsRequest, queryType: QueryType, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.similarQuery = query
        this.queryType = queryType
    }

    constructor(context: Context, query: GetUpcomingEpisodesRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.upcomingQuery = query
        this.queryType = QueryType.Upcoming
    }

    constructor(context: Context, query: GetSeasonsRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.seasonQuery = query
        this.queryType = QueryType.Season
    }

    constructor(context: Context, query: GetUserViewsRequest, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.queryType = QueryType.Views
        this.isStaticHeight = true
    }

    constructor(context: Context, query: GetResumeItemsRequest, chunkSize: Int, preferParentThumb: Boolean, staticHeight: Boolean, presenter: Presenter, parent: MutableObjectAdapter<Row>? = null) : super(presenter) {
        this.context = context
        this.parent = parent
        this.resumeQuery = query
        this.chunkSize = chunkSize
        this.preferParentThumb = preferParentThumb
        this.isStaticHeight = staticHeight
        this.queryType = QueryType.Resume
    }

    constructor(rowDef: BrowseRowDef, presenter: Presenter) : super(presenter) {
        initFromRowDef(rowDef)
    }

    constructor(rowDef: BrowseRowDef, presenterSelector: PresenterSelector) : super(presenterSelector) {
        initFromRowDef(rowDef)
    }

    constructor(query: GetItemsRequest, presenterSelector: PresenterSelector) : super(presenterSelector) {
        this.query = query
        this.queryType = QueryType.Items
    }

    constructor(presenter: Presenter) : super(presenter)

    constructor(presenterSelector: PresenterSelector) : super(presenterSelector)

    private fun initFromRowDef(rowDef: BrowseRowDef) {
        query = rowDef.query
        nextUpQuery = rowDef.nextUpQuery
        latestQuery = rowDef.latestItemsQuery
        tvChannelQuery = rowDef.tvChannelQuery
        tvProgramQuery = rowDef.programQuery
        tvRecordingQuery = rowDef.recordingQuery
        artistsQuery = rowDef.artistsQuery
        albumArtistsQuery = rowDef.albumArtistsQuery
        similarQuery = rowDef.similarQuery
        resumeQuery = rowDef.resumeQuery
        specialsQuery = rowDef.specialsQuery
        seriesTimerQuery = rowDef.seriesTimerQuery
        queryType = rowDef.queryType
        reRetrieveTriggers = rowDef.changeTriggers

        if (rowDef.items != null) {
            setItems(rowDef.items!!)
        }
    }

    private fun setCurrentlyRetrieving(currentlyRetrieving: Boolean) {
        synchronized(currentlyRetrievingSemaphore) {
            isCurrentlyRetrieving = currentlyRetrieving
        }
    }

    fun setSortBy(option: SortOption) {
        if (option.value != sortBy || option.order != sortOrder) {
            sortBy = option.value
            sortOrder = option.order
            when (queryType) {
                QueryType.Artists -> artistsQuery = setArtistsSorting(artistsQuery!!, option)
                QueryType.AlbumArtists -> albumArtistsQuery = setAlbumArtistsSorting(albumArtistsQuery!!, option)
                else -> query = setItemsSorting(query!!, option)
            }
            if (ItemSortBy.SORT_NAME != option.value) {
                setStartLetter(null)
            }
        }
    }

    fun getStartLetter(): String? = when (queryType) {
        QueryType.Artists -> artistsQuery?.nameStartsWith
        QueryType.AlbumArtists -> albumArtistsQuery?.nameStartsWith
        else -> query?.nameStartsWith
    }

    fun setStartLetter(value: String?) {
        val letter = if (value == "#") null else value
        when (queryType) {
            QueryType.Artists -> artistsQuery = setArtistsStartLetter(artistsQuery!!, letter)
            QueryType.AlbumArtists -> albumArtistsQuery = setAlbumArtistsStartLetter(albumArtistsQuery!!, letter)
            else -> query = setItemsStartLetter(query!!, letter)
        }
    }

    override fun remove(element: Any): Boolean {
        val removed = super.remove(element)
        if (removed) {
            itemsLoaded = size()
            if (size() == 0) {
                removeRow()
            }
        }
        return removed
    }

    fun removeRow() {
        val p = parent ?: run {
            clear()
            return
        }

        siblingRow?.let { p.remove(it) }

        if (p.size() == 1 && context != null) {
            val emptyRow = ArrayObjectAdapter(TextItemPresenter())
            emptyRow.add(context!!.getString(R.string.lbl_no_items))
            p.add(ListRow(HeaderItem(context!!.getString(R.string.lbl_empty)), emptyRow))
        }

        row?.let { p.remove(it) }
    }

    fun loadMoreItemsIfNeeded(pos: Int) {
        if (fullyLoaded || isCurrentlyRetrieving) return

        if (chunkSize > 0) {
            if (pos >= (itemsLoaded - (chunkSize / 1.7))) {
                Timber.i("Loading more items trigger pos <%s> itemsLoaded <%s> from total <%s> with chunkSize <%s>", pos, itemsLoaded, totalItems, chunkSize)
                retrieveNext()
            }
        } else if (pos >= itemsLoaded - 20) {
            Timber.i("Loading more items trigger pos <%s> itemsLoaded <%s> from total <%s>", pos, itemsLoaded, totalItems)
            retrieveNext()
        }
    }

    private fun retrieveNext() {
        if (fullyLoaded || isCurrentlyRetrieving || chunkSize == 0) return

        when (queryType) {
            QueryType.LiveTvChannel -> {
                val q = tvChannelQuery ?: return
                notifyRetrieveStarted()
                retrieveLiveTvChannels(api.value, q, itemsLoaded, chunkSize)
            }
            QueryType.Artists -> {
                val q = artistsQuery ?: return
                notifyRetrieveStarted()
                retrieveArtists(api.value, q, itemsLoaded, chunkSize)
            }
            QueryType.AlbumArtists -> {
                val q = albumArtistsQuery ?: return
                notifyRetrieveStarted()
                retrieveAlbumArtists(api.value, q, itemsLoaded, chunkSize)
            }
            else -> {
                val q = query ?: return
                notifyRetrieveStarted()
                retrieveItems(api.value, q, itemsLoaded, chunkSize)
            }
        }
    }

    fun reRetrieveIfNeeded(): Boolean {
        val triggers = reRetrieveTriggers ?: return false

        var retrieve = false
        val dataRefreshService: DataRefreshService = get(DataRefreshService::class.java)
        val lastRetrieve = lastFullRetrieve
        if (lastRetrieve != null) {
            for (trigger in triggers) {
                val changeTime = when (trigger) {
                    ChangeTriggerType.LibraryUpdated -> dataRefreshService.lastLibraryChange
                    ChangeTriggerType.MoviePlayback -> dataRefreshService.lastMoviePlayback
                    ChangeTriggerType.TvPlayback -> dataRefreshService.lastTvPlayback
                    ChangeTriggerType.FavoriteUpdate -> dataRefreshService.lastFavoriteUpdate
                    else -> null
                }
                if (changeTime != null && lastRetrieve.isBefore(changeTime)) {
                    retrieve = true
                }
            }
        }

        if (retrieve) {
            Timber.i("Re-retrieving row of type %s", queryType.toString())
            Retrieve()
        }

        return retrieve
    }

    fun Retrieve() {
        notifyRetrieveStarted()
        lastFullRetrieve = Instant.now()
        itemsLoaded = 0
        when (queryType) {
            QueryType.Items -> {
                val q = query ?: return
                if (q.startIndex != null && q.limit != null) {
                    retrieveItems(api.value, q, q.startIndex!!, q.limit!!)
                } else {
                    retrieveItems(api.value, q, 0, chunkSize)
                }
            }
            QueryType.NextUp -> nextUpQuery?.let { retrieveNextUpItems(api.value, it) }
            QueryType.LatestItems -> latestQuery?.let { retrieveLatestMedia(api.value, it) }
            QueryType.Upcoming -> upcomingQuery?.let { retrieveUpcomingEpisodes(api.value, it) }
            QueryType.Season -> seasonQuery?.let { retrieveSeasons(api.value, it) }
            QueryType.Views -> retrieveUserViews(api.value, userViewsRepository.value)
            QueryType.SimilarSeries, QueryType.SimilarMovies -> similarQuery?.let { retrieveSimilarItems(api.value, it) }
            QueryType.LiveTvChannel -> tvChannelQuery?.let { retrieveLiveTvChannels(api.value, it, 0, chunkSize) }
            QueryType.LiveTvProgram -> tvProgramQuery?.let { retrieveLiveTvRecommendedPrograms(api.value, it) }
            QueryType.LiveTvRecording -> tvRecordingQuery?.let { retrieveLiveTvRecordings(api.value, it) }
            QueryType.StaticPeople -> loadPeople()
            QueryType.StaticChapters -> loadChapters()
            QueryType.StaticItems -> loadStaticItems()
            QueryType.Specials -> specialsQuery?.let { retrieveSpecialFeatures(api.value, it) }
            QueryType.AdditionalParts -> additionalPartsQuery?.let { retrieveAdditionalParts(api.value, it) }
            QueryType.Trailers -> trailersQuery?.let { retrieveTrailers(api.value, it) }
            QueryType.Search -> {
                loadStaticItems()
                addToParentIfResultsReceived()
            }
            QueryType.Artists -> artistsQuery?.let { retrieveArtists(api.value, it, 0, chunkSize) }
            QueryType.AlbumArtists -> albumArtistsQuery?.let { retrieveAlbumArtists(api.value, it, 0, chunkSize) }
            QueryType.AudioPlaylists -> query?.let { retrieveAudioPlaylists(it) }
            QueryType.Premieres -> query?.let { retrievePremieres(api.value, it) }
            QueryType.SeriesTimer -> {
                val ctx = context
                if (ctx != null) {
                    val currentUser = get<UserRepository>(UserRepository::class.java).currentUser.value
                    val canManageRecordings = Utils.canManageRecordings(currentUser)
                    retrieveLiveTvSeriesTimers(api.value, ctx, canManageRecordings)
                }
            }
            QueryType.Resume -> resumeQuery?.let { retrieveResumeItems(api.value, it) }
            QueryType.NextEpisodes -> nextEpisodesQuery?.let { retrieveNextEpisodes(api.value, it) }
            else -> {}
        }
    }

    private fun loadPeople() {
        val p = persons
        if (p != null) {
            for (person in p) {
                add(BaseItemPersonBaseRowItem(person))
            }
        } else {
            removeRow()
        }
        notifyRetrieveFinished()
    }

    private fun loadChapters() {
        val c = chapters
        if (c != null) {
            for (chapter in c) {
                add(ChapterItemInfoBaseRowItem(chapter))
            }
        } else {
            removeRow()
        }
        notifyRetrieveFinished()
    }

    private fun loadStaticItems() {
        val i = initialItems
        if (i != null) {
            for (item in i) {
                add(BaseItemDtoBaseRowItem(item))
            }
            itemsLoaded = i.size
        } else if (size() > 0) {
            itemsLoaded = size()
        } else {
            removeRow()
        }
        notifyRetrieveFinished()
    }

    private fun addToParentIfResultsReceived() {
        val r = row
        if (itemsLoaded > 0 && parent != null && r != null) {
            parent!!.add(r)
        }
    }

    private fun retrieveAudioPlaylists(query: GetItemsRequest) {
        clear()
        if (context != null) {
            add(GridButtonBaseRowItem(GridButton(EnhancedBrowseFragment.FAVSONGS, context!!.getString(R.string.lbl_favorites), R.drawable.favorites)))
        }
        itemsLoaded = 1
        retrieveItems(api.value, query, 0, chunkSize)
    }

    @JvmOverloads
    fun notifyRetrieveFinished(exception: Exception? = null) {
        if (exception != null) Timber.w(exception, "Failed to retrieve items")

        setCurrentlyRetrieving(false)
        if (exception == null) {
            retrieveFinishedListener?.invoke()
        }
    }

    fun setRetrieveFinishedListener(listener: (() -> Unit)?) {
        this.retrieveFinishedListener = listener
    }

    private fun notifyRetrieveStarted() {
        setCurrentlyRetrieving(true)
    }

    fun setItems(newItems: List<Any>) {
        clear()
        for (item in newItems) {
            add(item)
        }
    }
}

package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.AppNotification
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.ensureUsbTilePresent
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager

import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.KeyProcessor
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.coroutineScope
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.presentation.config.getDisplayConfig
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.usbdevices.USB_GRID_BUTTON_ID
import org.jellyfin.androidtv.util.usbdevices.USB_TILE_UUID
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbGridButton
import org.jellyfin.androidtv.util.usbdevices.UsbHomeDecorator
import org.jellyfin.androidtv.util.usbdevices.UsbStorageManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class HomeViewModel(
    private val context: Context,
    private val api: ApiClient,
    private val backgroundService: BackgroundService,
    private val playbackManager: PlaybackManager,
    private val mediaManager: MediaManager,
    private val notificationsRepository: NotificationsRepository,
    private val userRepository: UserRepository,
    private val userPreferences: UserPreferences,
    private val userSettingPreferences: UserSettingPreferences,
    private val userViewsRepository: UserViewsRepository,
    private val dataRefreshService: DataRefreshService,
    private val customMessageRepository: CustomMessageRepository,
    private val navigationRepository: NavigationRepository,
    private val itemLauncher: ItemLauncher,
    private val keyProcessor: KeyProcessor,
    private val imageLoader: ImageLoader
) : ViewModel(), AudioEventListener {

    private val helper by lazy { HomeFragmentHelper(context, userRepository, userPreferences) }

    private val _directRowStates = mutableListOf<HomeRowState>()

    private val notificationsRow by lazy { NotificationsHomeFragmentRow(viewModelScope, notificationsRepository) }
    private val nowPlaying by lazy { HomeFragmentNowPlayingRow(viewModelScope, playbackManager, mediaManager) }

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Success(emptyList()))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _focusRequestTrigger = MutableStateFlow(0L)
    val focusRequestTrigger: StateFlow<Long> = _focusRequestTrigger.asStateFlow()

    fun requestFocus() {
        _focusRequestTrigger.value = System.currentTimeMillis()
    }

    private var currentItem: Any? = null
    private var justLoaded = true
    private var lastHandledPlayback: Instant? = null

    private fun addDirectRowState(rowState: HomeRowState) {
        synchronized(_directRowStates) {
            val index = _directRowStates.indexOfFirst { it.id == rowState.id }
            if (index >= 0) {
                _directRowStates[index] = rowState
            } else {
                _directRowStates.add(rowState)
            }
        }
        updateUiState()
    }

    private fun removeDirectRowState(rowState: HomeRowState) {
        synchronized(_directRowStates) {
            _directRowStates.removeAll { it.id == rowState.id }
        }
        updateUiState()
    }

    init {

        notificationsRow.addRowStates(context,
            onRowStateAdded = { addDirectRowState(it.copy(sectionIndex = -200)) },
            onRowStateRemoved = { removeDirectRowState(it) }
        )

        nowPlaying.addRowStates(context,
            onRowStateAdded = { addDirectRowState(it.copy(sectionIndex = -100)) },
            onRowStateRemoved = { removeDirectRowState(it) }
        )

        refreshRows(force = true, delayed = false)

        customMessageRepository.message
            .onEach { message ->
                if (message == CustomMessage.RefreshCurrentItem) {
                    refreshCurrentItem()
                }
            }
            .launchIn(viewModelScope)

        navigationRepository.currentAction
            .drop(1)
            .onEach { action ->
                if (action is NavigationAction.NavigateFragment && action.destination.fragment == HomeFragment::class) {
                    resetLastFocusedItem()
                    refreshRows(force = true, delayed = false)
                }
            }
            .launchIn(viewModelScope)

        mediaManager.addAudioEventListener(this)
    }

    private fun updateUiState() {
        val rowStates = synchronized(_directRowStates) {
            _directRowStates.sortedBy { it.sectionIndex }
        }
        _uiState.value = HomeUiState.Success(rowStates)
    }

    private var lastHandledRefresh: Instant? = null
    private var refreshJob: Job? = null

    private fun preloadHomeRowImages(items: List<Any>) {
        val baseItems = items.filterIsInstance<BaseRowItem>().take(12)
        if (baseItems.isEmpty()) return

        val density = context.resources.displayMetrics.density

        viewModelScope.launch(Dispatchers.IO) {
            for (item in baseItems) {
                val displayConfig = item.getDisplayConfig(
                    imageType = ImageType.POSTER,
                    uniformAspect = false,
                    displayCirclePersonCards = false
                )
                val image = displayConfig.image ?: continue

                val aspectRatio = displayConfig.aspectRatio.takeIf { it >= 0.1f }
                    ?: image.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

                val (widthDp, heightDp) = when {
                    item.staticHeight -> (150f * aspectRatio) to 150f
                    aspectRatio > 1f -> (130f * aspectRatio) to 130f
                    else -> (150f * aspectRatio) to 150f
                }

                val maxWidthPx = (widthDp * density).toInt().coerceAtLeast(1)
                val maxHeightPx = (heightDp * density).toInt().coerceAtLeast(1)

                val url = image.getUrl(
                    api = api,
                    maxWidth = maxWidthPx,
                    maxHeight = maxHeightPx,
                )

                val request = ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .build()

                imageLoader.enqueue(request)
            }
        }
    }

    private val loadedSectionRowIds = mutableMapOf<Int, List<String>>()

    private fun updateSectionRows(sIndex: Int, newRows: List<HomeRowState>) {
        for (rowState in newRows) {
            preloadHomeRowImages(rowState.items)
        }

        val newIds = newRows.map { it.id }
        val oldIds = synchronized(loadedSectionRowIds) {
            val prev = loadedSectionRowIds[sIndex].orEmpty()
            loadedSectionRowIds[sIndex] = newIds
            prev
        }

        val removedIds = oldIds.filterNot { it in newIds }

        synchronized(_directRowStates) {
            if (removedIds.isNotEmpty()) {
                _directRowStates.removeAll { it.id in removedIds }
            }
            for (rowState in newRows) {
                val index = _directRowStates.indexOfFirst { it.id == rowState.id }
                if (index >= 0) {
                    _directRowStates[index] = rowState
                } else {
                    _directRowStates.add(rowState)
                }
            }
        }
        updateUiState()
    }

    private fun loadHomeSections() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentUser = withTimeout(30.seconds) {
                userRepository.currentUser.filterNotNull().first()
            }

            val userViews = userViewsRepository.getCachedOrFetchedViews()
            if (!isActive) return@launch

            val homesections = userSettingPreferences.activeHomesections

            suspend fun loadSectionRows(section: HomeSectionType, sIndex: Int) {
                val baseSectionIndex = sIndex * 100
                val sectionRows = mutableListOf<HomeRowState>()

                when (section) {
                    HomeSectionType.LIBRARY_TILES_SMALL -> {
                        val r = HomeFragmentViewsRow(small = false)
                        val state = r.createRowState(context, userViewsRepository)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.LIBRARY_BUTTONS -> {
                        val r = HomeFragmentViewsRow(small = true)
                        val state = r.createRowState(context, userViewsRepository)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.RESUME -> {
                        val r = helper.loadResumeVideo() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.RESUME_AUDIO -> {
                        val r = helper.loadResumeAudio() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.ACTIVE_RECORDINGS -> {
                        val r = helper.loadLatestLiveTvRecordings() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.NEXT_UP -> {
                        val r = helper.loadNextUp() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                        }
                    }
                    HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
                        val liveTvRow = HomeFragmentLiveTVRow(context, userRepository)
                        liveTvRow.addRowStates(context,
                            onRowStateAdded = { state ->
                                sectionRows.add(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                            },
                            onRowStateRemoved = { }
                        )
                        val r = helper.loadOnNow() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) {
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex + 10, sectionType = section))
                        }
                    }
                    HomeSectionType.LATEST_MEDIA -> {
                        val latestRow = helper.loadRecentlyAdded(userViews) as HomeFragmentLatestRow
                        val states = latestRow.prepareRowStates(context, api)
                        states.forEachIndexed { vIdx, state ->
                            sectionRows.add(state.copy(sectionIndex = baseSectionIndex + vIdx, sectionType = section))
                        }
                    }
                    HomeSectionType.NONE, HomeSectionType.RESUME_BOOK -> Unit
                }

                if (!isActive) return
                updateSectionRows(sIndex, sectionRows)
            }

            coroutineScope {
                homesections.forEachIndexed { idx, section ->
                    launch { loadSectionRows(section, idx) }
                }
            }

            lastHandledRefresh = Instant.now()
            updateUiState()
        }
    }

    suspend fun subscribeWebSockets() = coroutineScope {
        launch(Dispatchers.IO) {
            api.webSocket.subscribe<UserDataChangedMessage>()
                .collect { message ->
                    val now = Instant.now()
                    dataRefreshService.lastPlayback = now
                    dataRefreshService.lastTvPlayback = now
                    dataRefreshService.lastMoviePlayback = now

                    val changedData = message.data
                    val changedUserId = changedData?.userId
                    val currentUserId = userRepository.currentUser.value?.id
                    if (changedUserId == null || changedUserId == currentUserId) {
                        changedData?.userDataList?.forEach { userData ->
                            refreshItemById(userData.itemId)
                        }
                    }

                    refreshRows(force = false, delayed = true)
                }
        }
        launch(Dispatchers.IO) {
            api.webSocket.subscribe<LibraryChangedMessage>()
                .collect {
                    dataRefreshService.lastLibraryChange = Instant.now()
                    userViewsRepository.clearCache()
                    refreshRows(force = false, delayed = true)
                }
        }
    }

    fun onResume() {
        requestFocus()
        val maxDataTimestamp = listOfNotNull(
            dataRefreshService.lastPlayback,
            dataRefreshService.lastMoviePlayback,
            dataRefreshService.lastTvPlayback,
            dataRefreshService.lastLibraryChange,
            dataRefreshService.lastFavoriteUpdate
        ).maxOrNull()

        val dataChanged = lastHandledRefresh != null && maxDataTimestamp != null && maxDataTimestamp.isAfter(lastHandledRefresh)
        if (dataChanged) {
            refreshRows(force = true, delayed = false)
        }

        nowPlaying.update()
    }

    override fun onQueueStatusChanged(hasQueue: Boolean) {
        nowPlaying.update()
    }

    fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (delayed) {
                delay(300)
            }
            loadHomeSections()
        }
    }

    fun refreshItemById(itemId: UUID) {
        refreshRows(force = false, delayed = true)
    }

    fun refreshCurrentItem() {
        refreshRows(force = true, delayed = false)
    }

    fun onItemClicked(item: Any, rowIndex: Int) {
        if (item is AppNotification) {
            notificationsRepository.dismissNotification(item)
            return
        }

        try {
            val isUsbTile = when (item) {
                is UsbBaseRowItem -> true
                is UsbGridButton -> true
                is GridButtonBaseRowItem -> item.gridButton.id == USB_GRID_BUTTON_ID
                is BaseRowItem -> item.baseItem?.id == USB_TILE_UUID
                else -> false
            }

            if (isUsbTile) {
                UsbHomeDecorator.handleUsbTileClick(context, navigationRepository)
                return
            }
        } catch (t: Throwable) {
            Timber.e(t, "Error handling USB tile click")
            return
        }

        val gridButton = when (item) {
            is GridButton -> item
            is GridButtonBaseRowItem -> item.gridButton
            else -> null
        }

        if (gridButton != null) {
            when (gridButton.id) {
                LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
                LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
                LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
                LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
            }
            return
        }

        if (item !is BaseRowItem) return
        itemLauncher.launch(item, null, context)
    }

    var lastFocusedItemKey: String? = null
        private set
    var lastFocusedRowIndex: Int = 0
        private set

    fun resetLastFocusedItem() {
        lastFocusedItemKey = null
        lastFocusedRowIndex = 0
    }

    fun onItemSelected(item: Any?, rowIndex: Int) {
        if (item != null) {
            lastFocusedItemKey = getItemKey(item)
            lastFocusedRowIndex = rowIndex
        }
        currentItem = item

        if (item !is BaseRowItem) {
            backgroundService.setSplashscreenBackground()
        } else {
            backgroundService.setBackground(item.baseItem, fallbackToSplashscreen = true)
        }
    }

    fun handleKey(keyCode: Int, activity: androidx.fragment.app.FragmentActivity?): Boolean {
        val item = currentItem
        if (item is BaseRowItem && activity != null) {
            return keyProcessor.handleKey(keyCode, item, activity)
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        mediaManager.removeAudioEventListener(this)
    }
}

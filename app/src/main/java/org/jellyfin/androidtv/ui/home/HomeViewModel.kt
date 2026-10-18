package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.usbdevices.USB_GRID_BUTTON_ID
import org.jellyfin.androidtv.util.usbdevices.USB_TILE_UUID
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbGridButton
import org.jellyfin.androidtv.util.usbdevices.UsbHomeDecorator
import org.jellyfin.androidtv.util.usbdevices.UsbStorageManager
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
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
    private val keyProcessor: KeyProcessor
) : ViewModel(), AudioEventListener {

    private val helper by lazy { HomeFragmentHelper(context, userRepository, userPreferences) }

    private val rowsAdapter = MutableObjectAdapter<Row>()
    private val rowSectionIndices = mutableMapOf<Row, Int>()

    private val notificationsRow by lazy { NotificationsHomeFragmentRow(viewModelScope, notificationsRepository) }
    private val nowPlaying by lazy { HomeFragmentNowPlayingRow(viewModelScope, playbackManager, mediaManager) }

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _focusRequestTrigger = MutableStateFlow(0L)
    val focusRequestTrigger: StateFlow<Long> = _focusRequestTrigger.asStateFlow()

    fun requestFocus() {
        _focusRequestTrigger.value = System.currentTimeMillis()
    }

    private var currentItem: Any? = null
    private var currentRow: ListRow? = null
    private var justLoaded = true

    init {
        rowsAdapter.registerObserver(object : androidx.leanback.widget.ObjectAdapter.DataObserver() {
            override fun onChanged() { updateUiState() }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { updateUiState() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateUiState() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateUiState() }
            override fun onItemMoved(fromPosition: Int, toPosition: Int) { updateUiState() }
        })

        val cardPresenter = CardPresenter()
        notificationsRow.addToRowsAdapter(context, cardPresenter, rowsAdapter)
        nowPlaying.addToRowsAdapter(context, cardPresenter, rowsAdapter)

        loadHomeSections(cardPresenter)

        customMessageRepository.message
            .onEach { message ->
                if (message == CustomMessage.RefreshCurrentItem) {
                    refreshCurrentItem()
                }
            }
            .launchIn(viewModelScope)

        navigationRepository.currentAction
            .onEach { action ->
                if (action is NavigationAction.NavigateFragment && action.destination.fragment == HomeFragment::class) {
                    resetLastFocusedItem()
                }
            }
            .launchIn(viewModelScope)

        mediaManager.addAudioEventListener(this)

        viewModelScope.launch {
            UsbStorageManager.mountedVolumes.collect { volumes ->
                var foundViewsRow = false
                for (i in 0 until rowsAdapter.size()) {
                    val listRow = (rowsAdapter[i] as? ListRow) ?: continue
                    val itemAdapter = (listRow.adapter as? ItemRowAdapter) ?: continue
                    if (itemAdapter.queryType == QueryType.Views) {
                        foundViewsRow = true
                        itemAdapter.ensureUsbTilePresent()
                    }
                }
                if (!foundViewsRow && volumes.isNotEmpty()) {
                    refreshRows(force = true, delayed = false)
                }
            }
        }

        subscribeWebSockets()
    }

    private fun updateUiState() {
        val rowStates = mutableListOf<HomeRowState>()
        for (i in 0 until rowsAdapter.size()) {
            val listRow = rowsAdapter[i] as? ListRow ?: continue
            val rowAdapter = listRow.adapter ?: continue
            val items = (0 until rowAdapter.size()).mapNotNull { rowAdapter.get(it) }
            val sectionIdx = getSectionIndex(listRow)
            val headerText = listRow.headerItem?.name

            rowStates.add(
                HomeRowState(
                    id = "row_${listRow.id}_$i",
                    title = headerText,
                    items = items,
                    sectionIndex = sectionIdx
                )
            )
        }
        _uiState.value = HomeUiState.Success(rowStates)
    }

    private fun getSectionIndex(row: Row): Int {
        return rowSectionIndices[row] ?: when (row) {
            notificationsRow.listRow -> -200
            nowPlaying.row -> -100
            else -> Int.MAX_VALUE
        }
    }

    private fun addRowInOrder(row: ListRow, sectionIndex: Int) {
        rowSectionIndices[row] = sectionIndex

        var insertPos = rowsAdapter.size()
        for (i in 0 until rowsAdapter.size()) {
            val existingRow = rowsAdapter[i] ?: continue
            val existingIndex = getSectionIndex(existingRow)
            if (existingIndex > sectionIndex) {
                insertPos = i
                break
            }
        }

        val itemAdapter = row.adapter as? ItemRowAdapter
        itemAdapter?.parent = rowsAdapter
        rowsAdapter.add(insertPos, row)

        if (itemAdapter?.queryType == QueryType.LiveTvProgram && insertPos > 0) {
            val previousRow = rowsAdapter[insertPos - 1]
            previousRow?.let { itemAdapter.setSiblingRow(it) }
        }
    }

    private fun loadHomeSections(cardPresenter: CardPresenter) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentUser = withTimeout(30.seconds) {
                userRepository.currentUser.filterNotNull().first()
            }

            val userViews = userViewsRepository.getCachedOrFetchedViews()
            if (!isActive) return@launch

            val homesections = userSettingPreferences.activeHomesections
            val primarySections = homesections.take(3)
            val secondarySections = homesections.drop(3)

            suspend fun loadSectionRows(section: HomeSectionType, sIndex: Int): List<Pair<ListRow, Int>> {
                val baseSectionIndex = sIndex * 100
                val rowsList = mutableListOf<Pair<ListRow, Int>>()

                when (section) {
                    HomeSectionType.LIBRARY_TILES_SMALL -> {
                        val r = HomeFragmentViewsRow(small = false)
                        val listRow = r.createListRow(context, userViewsRepository)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.LIBRARY_BUTTONS -> {
                        val r = HomeFragmentViewsRow(small = true)
                        val listRow = r.createListRow(context, userViewsRepository)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.RESUME -> {
                        val r = helper.loadResumeVideo() as HomeFragmentBrowseRowDefRow
                        val listRow = r.prepareRow(context, cardPresenter, api)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.RESUME_AUDIO -> {
                        val r = helper.loadResumeAudio() as HomeFragmentBrowseRowDefRow
                        val listRow = r.prepareRow(context, cardPresenter, api)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.ACTIVE_RECORDINGS -> {
                        val r = helper.loadLatestLiveTvRecordings() as HomeFragmentBrowseRowDefRow
                        val listRow = r.prepareRow(context, cardPresenter, api)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.NEXT_UP -> {
                        val r = helper.loadNextUp() as HomeFragmentBrowseRowDefRow
                        val listRow = r.prepareRow(context, cardPresenter, api)
                        if (listRow != null) rowsList.add(listRow to baseSectionIndex)
                    }
                    HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
                        val liveTvRow = HomeFragmentLiveTVRow(context, userRepository)
                        val tempAdapter = MutableObjectAdapter<Row>()
                        liveTvRow.addToRowsAdapter(context, cardPresenter, tempAdapter)
                        (0 until tempAdapter.size()).mapNotNull { tempAdapter[it] as? ListRow }.forEachIndexed { idx, row ->
                            rowsList.add(row to (baseSectionIndex + idx))
                        }
                        val r = helper.loadOnNow() as HomeFragmentBrowseRowDefRow
                        val listRow = r.prepareRow(context, cardPresenter, api)
                        if (listRow != null) rowsList.add(listRow to (baseSectionIndex + 10))
                    }
                    HomeSectionType.LATEST_MEDIA -> {
                        val latestRow = helper.loadRecentlyAdded(userViews) as HomeFragmentLatestRow
                        latestRow.prepareRows(context, cardPresenter, api).forEachIndexed { vIdx, listRow ->
                            rowsList.add(listRow to (baseSectionIndex + vIdx))
                        }
                    }
                    HomeSectionType.NONE, HomeSectionType.RESUME_BOOK -> Unit
                }

                return rowsList
            }

            val primaryRows = primarySections.mapIndexed { idx, section ->
                async { loadSectionRows(section, idx) }
            }.awaitAll().flatten()

            if (primaryRows.isNotEmpty() && isActive) {
                withContext(Dispatchers.Main) {
                    primaryRows.sortedBy { it.second }.forEach { (row, sectionIndex) ->
                        addRowInOrder(row, sectionIndex)
                    }
                }
            }

            if (secondarySections.isNotEmpty() && isActive) {
                val secondaryRows = secondarySections.mapIndexed { idx, section ->
                    async { loadSectionRows(section, idx + primarySections.size) }
                }.awaitAll().flatten()

                if (secondaryRows.isNotEmpty() && isActive) {
                    withContext(Dispatchers.Main) {
                        secondaryRows.sortedBy { it.second }.forEach { (row, sectionIndex) ->
                            addRowInOrder(row, sectionIndex)
                        }
                    }
                }
            }
        }
    }

    private fun subscribeWebSockets() {
        viewModelScope.launch {
            api.webSocket.subscribe<UserDataChangedMessage>()
                .onEach { message ->
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
                .launchIn(this)

            api.webSocket.subscribe<LibraryChangedMessage>()
                .onEach {
                    dataRefreshService.lastLibraryChange = Instant.now()
                    userViewsRepository.clearCache()
                    refreshRows(force = false, delayed = true)
                }
                .launchIn(this)
        }
    }

    fun onResume() {
        requestFocus()
        val deletedId = dataRefreshService.lastDeletedItemId
        val currItem = currentItem
        if ((deletedId != null) && (currItem is BaseRowItem) && (currItem.baseItem?.id == deletedId)) {
            (currentRow?.adapter as? ItemRowAdapter)?.remove(currItem)
            currentItem = null
            dataRefreshService.lastDeletedItemId = null
        }

        for (i in 0 until rowsAdapter.size()) {
            val listRow = (rowsAdapter[i] as? ListRow) ?: continue
            val itemAdapter = (listRow.adapter as? ItemRowAdapter) ?: continue
            if (itemAdapter.queryType == QueryType.Views) {
                itemAdapter.ensureUsbTilePresent()
            }
        }

        if (!justLoaded) {
            refreshCurrentItem()
            refreshRows()
        } else {
            justLoaded = false
        }

        nowPlaying.update(context, rowsAdapter)
    }

    override fun onQueueStatusChanged(hasQueue: Boolean) {
        nowPlaying.update(context, rowsAdapter)
    }

    fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            if (delayed) delay(1.5.seconds)
            val minInterval = Instant.now().minusSeconds(300)

            repeat(rowsAdapter.size()) { i ->
                val rowAdapter = ((rowsAdapter[i] as? ListRow)?.adapter as? ItemRowAdapter) ?: return@repeat
                if (rowAdapter.queryType == QueryType.Views) return@repeat

                if (force) {
                    rowAdapter.Retrieve()
                } else {
                    val lastRetrieve = rowAdapter.lastFullRetrieve
                    if (lastRetrieve == null || lastRetrieve.isBefore(minInterval)) {
                        rowAdapter.ReRetrieveIfNeeded()
                    }
                }
            }
        }
    }

    fun refreshItemById(itemId: UUID) {
        repeat(rowsAdapter.size()) { i ->
            val listRow = rowsAdapter[i] as? ListRow ?: return@repeat
            val itemAdapter = listRow.adapter as? ItemRowAdapter ?: return@repeat
            repeat(itemAdapter.size()) { j ->
                val rowItem = itemAdapter[j] as? BaseRowItem ?: return@repeat
                if (rowItem.itemId == itemId) {
                    itemAdapter.refreshItem(api, viewModelScope, rowItem)
                }
            }
        }
    }

    fun refreshCurrentItem() {
        val adapter = (currentRow?.adapter as? ItemRowAdapter) ?: return
        val item = currentItem as? BaseRowItem ?: return
        adapter.refreshItem(api, viewModelScope, item)
    }

    fun onItemClicked(item: Any, rowIndex: Int) {
        val listRow = if (rowIndex in 0 until rowsAdapter.size()) rowsAdapter[rowIndex] as? ListRow else null
        if (listRow == null) return

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
        @Suppress("UNCHECKED_CAST")
        val itemAdapter = (listRow.adapter as? MutableObjectAdapter<Any>) ?: return
        itemLauncher.launch(item, itemAdapter, context)
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
        val listRow = if (rowIndex in 0 until rowsAdapter.size()) rowsAdapter[rowIndex] as? ListRow else null
        if (item !is BaseRowItem) {
            currentItem = item
            backgroundService.clearBackgrounds()
        } else {
            currentItem = item
            currentRow = listRow

            val itemRowAdapter = listRow?.adapter as? ItemRowAdapter
            itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

            backgroundService.setBackground(item.baseItem)
        }
    }

    fun handleKey(keyCode: Int): Boolean {
        val item = currentItem
        if (item is BaseRowItem) {
            return keyProcessor.handleKey(keyCode, item, null)
        }
        return false
    }

    override fun onCleared() {
        super.onCleared()
        mediaManager.removeAudioEventListener(this)
    }
}

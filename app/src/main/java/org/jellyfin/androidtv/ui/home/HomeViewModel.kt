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

    private val _directRowStates = mutableListOf<HomeRowState>()

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
    private var justLoaded = true
    private var lastHandledPlayback: Instant? = null

    private fun addDirectRowState(rowState: HomeRowState) {
        val index = _directRowStates.indexOfFirst { it.id == rowState.id }
        if (index >= 0) {
            _directRowStates[index] = rowState
        } else {
            _directRowStates.add(rowState)
        }
        updateUiState()
    }

    private fun removeDirectRowState(rowState: HomeRowState) {
        _directRowStates.removeAll { it.id == rowState.id }
        updateUiState()
    }

    init {
        val cardPresenter = CardPresenter()
        notificationsRow.addRowStates(context, cardPresenter,
            onRowStateAdded = { addDirectRowState(it.copy(sectionIndex = -200)) },
            onRowStateRemoved = { removeDirectRowState(it) }
        )

        nowPlaying.addRowStates(context, cardPresenter,
            onRowStateAdded = { addDirectRowState(it.copy(sectionIndex = -100)) },
            onRowStateRemoved = { removeDirectRowState(it) }
        )

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

        subscribeWebSockets()
    }

    private fun updateUiState() {
        val rowStates = _directRowStates.sortedBy { it.sectionIndex }
        _uiState.value = HomeUiState.Success(rowStates)
    }

    private fun loadHomeSections(cardPresenter: CardPresenter) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentUser = withTimeout(30.seconds) {
                userRepository.currentUser.filterNotNull().first()
            }

            val userViews = userViewsRepository.getCachedOrFetchedViews()
            if (!isActive) return@launch

            val homesections = userSettingPreferences.activeHomesections

            suspend fun loadSectionRows(section: HomeSectionType, sIndex: Int) {
                val baseSectionIndex = sIndex * 100

                when (section) {
                    HomeSectionType.LIBRARY_TILES_SMALL -> {
                        val r = HomeFragmentViewsRow(small = false)
                        val state = r.createRowState(context, userViewsRepository)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.LIBRARY_BUTTONS -> {
                        val r = HomeFragmentViewsRow(small = true)
                        val state = r.createRowState(context, userViewsRepository)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.RESUME -> {
                        val r = helper.loadResumeVideo() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.RESUME_AUDIO -> {
                        val r = helper.loadResumeAudio() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.ACTIVE_RECORDINGS -> {
                        val r = helper.loadLatestLiveTvRecordings() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.NEXT_UP -> {
                        val r = helper.loadNextUp() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex, sectionType = section))
                    }
                    HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
                        val liveTvRow = HomeFragmentLiveTVRow(context, userRepository)
                        liveTvRow.addRowStates(context, cardPresenter,
                            onRowStateAdded = { addDirectRowState(it.copy(sectionIndex = baseSectionIndex, sectionType = section)) },
                            onRowStateRemoved = { removeDirectRowState(it) }
                        )
                        val r = helper.loadOnNow() as HomeFragmentBrowseRowDefRow
                        val state = r.prepareRowState(context, api)
                        if (state != null) addDirectRowState(state.copy(sectionIndex = baseSectionIndex + 10, sectionType = section))
                    }
                    HomeSectionType.LATEST_MEDIA -> {
                        val latestRow = helper.loadRecentlyAdded(userViews) as HomeFragmentLatestRow
                        latestRow.prepareRowStates(context, api).forEachIndexed { vIdx, state ->
                            addDirectRowState(state.copy(sectionIndex = baseSectionIndex + vIdx, sectionType = section))
                        }
                    }
                    HomeSectionType.NONE, HomeSectionType.RESUME_BOOK -> Unit
                }
            }

            homesections.mapIndexed { idx, section ->
                async { loadSectionRows(section, idx) }
            }.awaitAll()
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
        val lastPlayback = dataRefreshService.lastPlayback
        val playbackChanged = lastPlayback != null && (lastHandledPlayback == null || lastPlayback.isAfter(lastHandledPlayback))
        if (playbackChanged) {
            lastHandledPlayback = lastPlayback
        }

        nowPlaying.update()
    }

    override fun onQueueStatusChanged(hasQueue: Boolean) {
        nowPlaying.update()
    }

    fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
        // Compose handles UI refresh via flow updates
    }

    fun refreshItemById(itemId: UUID) {
        // Compose handles UI refresh via flow updates
    }

    fun refreshCurrentItem() {
        // Compose handles UI refresh via flow updates
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

    private var backgroundUpdateJob: Job? = null

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

        backgroundUpdateJob?.cancel()
        backgroundUpdateJob = viewModelScope.launch {
            delay(200)
            if (item !is BaseRowItem) {
                backgroundService.clearBackgrounds()
            } else {
                backgroundService.setBackground(item.baseItem)
            }
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

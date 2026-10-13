package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.ensureUsbTilePresent
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.androidtv.util.Utils
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
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userPreferences by inject<UserPreferences>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository, userPreferences) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }

	private val sharedRecycledViewPool by lazy {
		RecyclerView.RecycledViewPool().apply {
			setMaxRecycledViews(0, 25)
		}
	}

	@Suppress("UNCHECKED_CAST")
	private val rowsAdapter: MutableObjectAdapter<Row>?
		get() = adapter as? MutableObjectAdapter<Row>

	private val rowSectionIndices = mutableMapOf<Row, Int>()

	private fun getSectionIndex(row: Row): Int {
		return rowSectionIndices[row] ?: when (row) {
			notificationsRow.listRow -> -200
			nowPlaying.row -> -100
			else -> Int.MAX_VALUE
		}
	}

	private fun addRowInOrder(row: ListRow, sectionIndex: Int) {
		val targetAdapter = rowsAdapter ?: return
		rowSectionIndices[row] = sectionIndex

		var insertPos = targetAdapter.size()
		for (i in 0 until targetAdapter.size()) {
			val existingRow = targetAdapter[i] ?: continue
			val existingIndex = getSectionIndex(existingRow)
			if (existingIndex > sectionIndex) {
				insertPos = i
				break
			}
		}

		val itemAdapter = row.adapter as? ItemRowAdapter
		itemAdapter?.parent = targetAdapter
		targetAdapter.add(insertPos, row)

		if (itemAdapter?.queryType == QueryType.LiveTvProgram && insertPos > 0) {
			val previousRow = targetAdapter[insertPos - 1]
			previousRow?.let { itemAdapter.setSiblingRow(it) }
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter(sharedRecycledViewPool))

		val cardPresenter = CardPresenter()
		rowsAdapter?.let { targetAdapter ->
			notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, targetAdapter)
			nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, targetAdapter)
		}

		lifecycleScope.launch(Dispatchers.IO) {
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
						val listRow = r.createListRow(requireContext(), userViewsRepository)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.LIBRARY_BUTTONS -> {
						val r = HomeFragmentViewsRow(small = true)
						val listRow = r.createListRow(requireContext(), userViewsRepository)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.RESUME -> {
						val r = helper.loadResumeVideo() as HomeFragmentBrowseRowDefRow
						val listRow = r.prepareRow(requireContext(), cardPresenter, api)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.RESUME_AUDIO -> {
						val r = helper.loadResumeAudio() as HomeFragmentBrowseRowDefRow
						val listRow = r.prepareRow(requireContext(), cardPresenter, api)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.ACTIVE_RECORDINGS -> {
						val r = helper.loadLatestLiveTvRecordings() as HomeFragmentBrowseRowDefRow
						val listRow = r.prepareRow(requireContext(), cardPresenter, api)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.NEXT_UP -> {
						val r = helper.loadNextUp() as HomeFragmentBrowseRowDefRow
						val listRow = r.prepareRow(requireContext(), cardPresenter, api)
						if (listRow != null) rowsList.add(listRow to baseSectionIndex)
					}
					HomeSectionType.LIVE_TV -> if (currentUser.policy?.enableLiveTvAccess == true) {
						val liveTvRow = HomeFragmentLiveTVRow(requireActivity(), userRepository)
						val tempAdapter = MutableObjectAdapter<Row>()
						liveTvRow.addToRowsAdapter(requireContext(), cardPresenter, tempAdapter)
						(0 until tempAdapter.size()).mapNotNull { tempAdapter[it] as? ListRow }.forEachIndexed { idx, row ->
							rowsList.add(row to (baseSectionIndex + idx))
						}
						val r = helper.loadOnNow() as HomeFragmentBrowseRowDefRow
						val listRow = r.prepareRow(requireContext(), cardPresenter, api)
						if (listRow != null) rowsList.add(listRow to (baseSectionIndex + 10))
					}
					HomeSectionType.LATEST_MEDIA -> {
						val latestRow = helper.loadRecentlyAdded(userViews) as HomeFragmentLatestRow
						latestRow.prepareRows(requireContext(), cardPresenter, api).forEachIndexed { vIdx, listRow ->
							rowsList.add(listRow to (baseSectionIndex + vIdx))
						}
					}
					HomeSectionType.NONE, HomeSectionType.RESUME_BOOK -> Unit
				}

				return rowsList
			}

			// Phase 1: Load primary top visible sections concurrently
			val primaryRows = primarySections.mapIndexed { idx, section ->
				async { loadSectionRows(section, idx) }
			}.awaitAll().flatten()

			if (primaryRows.isNotEmpty() && isActive) {
				withContext(Dispatchers.Main) {
					verticalGridView?.let { gridView ->
						runCatching {
							CardPresenter().prewarm(gridView, sharedRecycledViewPool, count = 10)
						}
					}
					primaryRows.sortedBy { it.second }.forEach { (row, sectionIndex) ->
						addRowInOrder(row, sectionIndex)
					}
				}
			}

			// Phase 2: Load secondary sections below the fold
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

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Optimization for vertical scrolling: prevent rows from being unloaded immediately
		verticalGridView?.setItemViewCacheSize(10)

		// Pre-warm Compose card views into sharedRecycledViewPool immediately on view creation
		verticalGridView?.let { gridView ->
			runCatching {
				CardPresenter().prewarm(gridView, sharedRecycledViewPool, count = 10)
			}
		}

		// Dynamically update USB tile presence when USB drive is attached or removed
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				UsbStorageManager.mountedVolumes.collect { volumes ->
					val currentAdapter = rowsAdapter ?: return@collect
					var foundViewsRow = false
					for (i in 0 until currentAdapter.size()) {
						val listRow = (currentAdapter[i] as? ListRow) ?: continue
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
		}
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		// React to deletion
		val deletedId = dataRefreshService.lastDeletedItemId
		if ((deletedId != null) && (currentItem?.baseItem?.id == deletedId)) {
			(currentRow?.adapter as? ItemRowAdapter)?.remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		// Re-evaluate USB storage state on return to home
		val currentRowsAdapter = rowsAdapter
		currentRowsAdapter?.let { adapter ->
			for (i in 0 until adapter.size()) {
				val listRow = (adapter[i] as? ListRow) ?: continue
				val itemAdapter = (listRow.adapter as? ItemRowAdapter) ?: continue
				if (itemAdapter.queryType == QueryType.Views) {
					itemAdapter.ensureUsbTilePresent()
				}
			}
		}

		// Restore focus to current row / item if returning to home
		if ((currentRowsAdapter != null) && (currentRow != null)) {
			val rowIndex = currentRowsAdapter.indexOf(currentRow as Row)
			if (rowIndex >= 0) {
				selectedPosition = rowIndex
				verticalGridView?.post {
					val rowVh = findRowViewHolderByPosition(rowIndex) as? ListRowPresenter.ViewHolder
					if (rowVh != null) {
						val itemAdapter = currentRow?.adapter as? ItemRowAdapter
						if ((itemAdapter != null) && (currentItem != null)) {
							val itemIndex = itemAdapter.indexOf(currentItem)
							if (itemIndex >= 0) {
								rowVh.gridView.selectedPosition = itemIndex
							}
						}
						rowVh.gridView.requestFocus()
					}
				}
			}
		}

		if (!justLoaded) {
			// Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		currentRowsAdapter?.let { nowPlaying.update(requireContext(), it) }
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if ((activity == null) || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		rowsAdapter?.let { nowPlaying.update(requireContext(), it) }
	}

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			// 5-minute interval threshold to avoid wiping/re-fetching all rows on short home visits
			val minInterval = Instant.now().minusSeconds(300)

			repeat(adapter.size()) { i ->
				val rowAdapter = ((adapter[i] as? ListRow)?.adapter as? ItemRowAdapter) ?: return@repeat
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

	private fun refreshItemById(itemId: java.util.UUID) {
		val currentRowsAdapter = rowsAdapter ?: return
		repeat(currentRowsAdapter.size()) { i ->
			val listRow = currentRowsAdapter[i] as? ListRow ?: return@repeat
			val itemAdapter = listRow.adapter as? ItemRowAdapter ?: return@repeat
			repeat(itemAdapter.size()) { j ->
				val rowItem = itemAdapter[j] as? BaseRowItem ?: return@repeat
				if (rowItem.itemId == itemId) {
					itemAdapter.refreshItem(api, this, rowItem)
				}
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = (currentRow?.adapter as? ItemRowAdapter) ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			try {
				val isUsbTile = when (item) {
					is UsbBaseRowItem -> true
					is UsbGridButton -> true
					is GridButtonBaseRowItem -> item.gridButton.id == USB_GRID_BUTTON_ID
					is BaseRowItem -> item.baseItem?.id == USB_TILE_UUID
					else -> false
				}

				if (isUsbTile) {
					try {
						val activityContext = activity ?: requireContext()
						UsbHomeDecorator.handleUsbTileClick(activityContext, navigationRepository)
					} catch (t: Throwable) {
						Toast.makeText(
							context,
							"Erreur ouverture USB: ${t.javaClass.simpleName} - ${t.message}",
							Toast.LENGTH_LONG,
						).show()
					}
					return
				}
			} catch (t: Throwable) {
				Toast.makeText(
					context,
					"Erreur détection USB: ${t.javaClass.simpleName} - ${t.message}",
					Toast.LENGTH_LONG,
				).show()
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
			if (row !is ListRow) return
			@Suppress("UNCHECKED_CAST")
			val itemAdapter = (row.adapter as? MutableObjectAdapter<Any>) ?: return
			itemLauncher.launch(item, itemAdapter, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

				backgroundService.setBackground(item.baseItem)
			}
		}
	}
}

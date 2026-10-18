package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.PosterSize
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.setItemsSorting
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.presentation.BaseRowItemCard
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class BrowseGridFragment : Fragment() {
	private var folder: BaseItemDto? = null
	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()
	private val navigationRepository by inject<NavigationRepository>()
	private val preferencesRepository by inject<PreferencesRepository>()

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val folderJson = arguments?.getString(Extras.Folder) ?: return
		runCatching {
			folder = json.decodeFromString<BaseItemDto>(folderJson)
		}.onFailure {
			timber.log.Timber.e(it, "Failed to parse folder DTO in BrowseGridFragment")
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			isFocusable = true
			isFocusableInTouchMode = true
			setContent {
				JellyfinTheme {
					BrowseGridScreen(
						folder = folder,
						preferencesRepository = preferencesRepository,
						onItemClick = { item, adapter ->
							itemLauncher.launch(item, adapter, requireContext())
						},
						onItemFocused = { item ->
							if (item != null) {
								backgroundService.setBackground(item.baseItem)
							} else {
								backgroundService.clearBackgrounds()
							}
						},
						onHomeClick = {
							navigationRepository.reset(Destinations.home, clearHistory = true)
						}
					)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.post {
			view.requestFocus()
		}
	}
}

fun getGridColumnCount(
	imageType: ImageType,
	posterSize: PosterSize,
	gridDirection: GridDirection
): GridCells {
	return if (gridDirection == GridDirection.VERTICAL) {
		when (imageType) {
			ImageType.BANNER -> GridCells.Fixed(3)
			ImageType.THUMB -> GridCells.Fixed(5)
			else -> when (posterSize) {
				PosterSize.SMALLEST -> GridCells.Fixed(15)
				PosterSize.SMALL -> GridCells.Fixed(13)
				PosterSize.MED -> GridCells.Fixed(11)
				PosterSize.LARGE -> GridCells.Fixed(7)
				PosterSize.X_LARGE -> GridCells.Fixed(5)
			}
		}
	} else {
		when (imageType) {
			ImageType.BANNER, ImageType.THUMB -> GridCells.Fixed(3)
			else -> GridCells.Fixed(2)
		}
	}
}

@Composable
fun FilterPanel(
	availableGenres: Map<String, Int>,
	availableAudio: Map<String, Int>,
	availableTypes: Map<String, Int>,
	selectedGenre: String?,
	selectedAudio: String?,
	selectedType: String?,
	isMoviesLibrary: Boolean,
	onSelectGenre: (String?) -> Unit,
	onSelectAudio: (String?) -> Unit,
	onSelectType: (String?) -> Unit,
	onResetFilters: () -> Unit,
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.padding(horizontal = 40.dp, vertical = 6.dp)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (selectedGenre != null || selectedAudio != null || selectedType != null) {
			IconButton(onClick = onResetFilters) {
				Text(
					text = stringResource(R.string.lbl_Reset),
					color = Color.Red,
					fontSize = 13.sp,
					fontWeight = FontWeight.Bold
				)
			}
		}

		if (availableAudio.isNotEmpty()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "${stringResource(R.string.lbl_filter_audio)}:",
					fontSize = 13.sp,
					fontWeight = FontWeight.Bold,
					color = Color.LightGray
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					itemsIndexed(availableAudio.keys.toList().sorted()) { _, lang ->
						val count = availableAudio[lang] ?: 0
						val isSelected = selectedAudio == lang
						IconButton(onClick = { onSelectAudio(if (isSelected) null else lang) }) {
							Text(
								text = if (count > 0) "$lang ($count)" else lang,
								color = if (isSelected) JellyfinTheme.colorScheme.buttonActive else Color.White,
								fontSize = 12.sp,
								fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
							)
						}
					}
				}
			}
		}

		if (availableGenres.isNotEmpty()) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "${stringResource(R.string.lbl_genres)}:",
					fontSize = 13.sp,
					fontWeight = FontWeight.Bold,
					color = Color.LightGray
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					itemsIndexed(availableGenres.keys.toList().sorted()) { _, genre ->
						val count = availableGenres[genre] ?: 0
						val isSelected = selectedGenre == genre
						IconButton(onClick = { onSelectGenre(if (isSelected) null else genre) }) {
							Text(
								text = if (count > 0) "$genre ($count)" else genre,
								color = if (isSelected) JellyfinTheme.colorScheme.buttonActive else Color.White,
								fontSize = 12.sp,
								fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
							)
						}
					}
				}
			}
		}

		if (isMoviesLibrary) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = "${stringResource(R.string.lbl_filter_media_type)}:",
					fontSize = 13.sp,
					fontWeight = FontWeight.Bold,
					color = Color.LightGray
				)
				LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					itemsIndexed(listOf("4K", "HD", "3D")) { _, type ->
						val count = availableTypes[type] ?: 0
						val isSelected = selectedType == type
						IconButton(onClick = { onSelectType(if (isSelected) null else type) }) {
							Text(
								text = if (count > 0) "$type ($count)" else type,
								color = if (isSelected) JellyfinTheme.colorScheme.buttonActive else Color.White,
								fontSize = 12.sp,
								fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
							)
						}
					}
				}
			}
		}
	}
}

@Composable
fun AlphaPickerButton(
	letter: Char,
	selected: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	val backgroundColor = when {
		selected -> JellyfinTheme.colorScheme.buttonActive
		focused -> JellyfinTheme.colorScheme.buttonFocused
		else -> Color.Transparent
	}

	val textColor = when {
		selected -> JellyfinTheme.colorScheme.onButtonActive
		focused -> JellyfinTheme.colorScheme.onButtonFocused
		else -> Color.White
	}

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.size(32.dp)
			.clip(CircleShape)
			.background(backgroundColor, CircleShape)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick
			)
	) {
		Text(
			text = letter.toString(),
			style = TextStyle(
				fontSize = 14.sp,
				fontWeight = FontWeight.Bold,
				textAlign = TextAlign.Center
			),
			color = textColor
		)
	}
}

@Composable
fun BrowseGridScreen(
	folder: BaseItemDto?,
	preferencesRepository: PreferencesRepository,
	onItemClick: (BaseRowItem, ItemRowAdapter) -> Unit,
	onItemFocused: (BaseRowItem?) -> Unit,
	onHomeClick: () -> Unit
) {
	val context = LocalContext.current
	@Suppress("DEPRECATION")
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val itemsState = remember { mutableStateListOf<BaseRowItem>() }
	val adapterRef = remember { mutableStateOf<ItemRowAdapter?>(null) }

	var selectedItem by remember { mutableStateOf<BaseRowItem?>(null) }
	var selectedIndex by remember { mutableStateOf(0) }
	val firstCardFocusRequester = remember { FocusRequester() }
	var hasRequestedFocus by remember { mutableStateOf(false) }

	// Toolbar filter & tool states
	var isUnwatchedOnly by remember { mutableStateOf(false) }
	var isFavoritesOnly by remember { mutableStateOf(false) }
	var showAlphaPicker by remember { mutableStateOf(false) }
	var showSortBar by remember { mutableStateOf(false) }
	var showFilterBar by remember { mutableStateOf(false) }
	var showSettingsDialog by remember { mutableStateOf(false) }
	var selectedLetter by remember { mutableStateOf<Char?>(null) }
	var selectedGenre by remember { mutableStateOf<String?>(null) }
	var selectedAudio by remember { mutableStateOf<String?>(null) }
	var selectedType by remember { mutableStateOf<String?>(null) }
	var availableGenres by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
	var availableAudio by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
	var availableTypes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

	val api = koinInject<ApiClient>()

	val displayPrefsId = remember(folder) { folder?.displayPreferencesId ?: folder?.id?.toString() }
	val libraryPreferences = remember(displayPrefsId) {
		displayPrefsId?.let { preferencesRepository.getLibraryPreferences(it) }
	}

	val initialSortBy = remember(libraryPreferences) {
		libraryPreferences?.get(LibraryPreferences.sortBy) ?: ItemSortBy.SORT_NAME
	}
	val initialSortOrder = remember(libraryPreferences) {
		libraryPreferences?.get(LibraryPreferences.sortOrder) ?: SortOrder.ASCENDING
	}

	var currentSortBy by remember(initialSortBy) { mutableStateOf(initialSortBy) }
	var currentSortOrder by remember(initialSortOrder) { mutableStateOf(initialSortOrder) }

	val updateSort: (ItemSortBy, SortOrder) -> Unit = { newSortBy, newSortOrder ->
		currentSortBy = newSortBy
		currentSortOrder = newSortOrder
		libraryPreferences?.set(LibraryPreferences.sortBy, newSortBy)
		libraryPreferences?.set(LibraryPreferences.sortOrder, newSortOrder)
	}

	val imageType = libraryPreferences?.get(LibraryPreferences.imageType) ?: ImageType.POSTER
	val gridDirection = libraryPreferences?.get(LibraryPreferences.gridDirection) ?: GridDirection.VERTICAL

	val lifecycleOwner = LocalLifecycleOwner.current
	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				try { firstCardFocusRequester.requestFocus() } catch (e: Exception) {}
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	LaunchedEffect(folder) {
		val folderId = folder?.id ?: return@LaunchedEffect
		FilterLoader.loadGenres(api, folderId) { genresMap ->
			availableGenres = genresMap
		}
		FilterLoader.loadAudioLanguages(api, folderId) { audioMap ->
			availableAudio = audioMap
		}
		if (folder?.collectionType == CollectionType.MOVIES) {
			FilterLoader.loadResolutionCounts(api, folderId) { countsMap ->
				availableTypes = countsMap
			}
		}
	}

	LaunchedEffect(folder, isUnwatchedOnly, isFavoritesOnly, selectedLetter, selectedGenre, selectedAudio, selectedType, currentSortBy, currentSortOrder) {
		val activeFolder = folder ?: return@LaunchedEffect
		val filterSet = mutableSetOf<ItemFilter>()
		if (isUnwatchedOnly) filterSet.add(ItemFilter.IS_UNPLAYED)
		if (isFavoritesOnly) filterSet.add(ItemFilter.IS_FAVORITE)

		val sortOption = SortOption("Sort", currentSortBy, currentSortOrder)
		val request = setItemsSorting(
			FilterLoader.createFilteredGridRequest(
				folder = activeFolder,
				genres = listOfNotNull(selectedGenre),
				audioLanguages = listOfNotNull(selectedAudio),
				is4k = if (selectedType == "4K") true else null,
				isHd = if (selectedType == "HD") true else null,
				is3d = if (selectedType == "3D") true else null,
				itemFilters = if (filterSet.isNotEmpty()) filterSet.toList() else null
			).copy(
				nameStartsWith = selectedLetter?.toString()
			),
			sortOption
		)
		val cardPresenter = CardPresenter(false, imageType, 150)
		val adapter = ItemRowAdapter(
			context,
			request,
			100,
			false,
			false,
			cardPresenter,
			null,
			QueryType.Items
		)
		adapterRef.value = adapter
		adapter.setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
			override fun onResponse() {
				val loaded = (0 until adapter.size()).mapNotNull { adapter.get(it) as? BaseRowItem }
				itemsState.clear()
				itemsState.addAll(loaded)
				if (selectedItem == null && loaded.isNotEmpty()) {
					selectedItem = loaded.first()
					onItemFocused(loaded.first())
				}
			}
		})
		adapter.Retrieve()
	}

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			// Top Header Bar: Title on Left, Home button + Clock on Right
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 40.dp, end = 40.dp, top = 20.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = selectedItem?.getCardName(context) ?: folder?.name ?: "",
					style = JellyfinTheme.typography.default.copy(
						fontSize = 32.sp,
						fontWeight = FontWeight.Light
					),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					color = Color.White,
					modifier = Modifier.weight(1f, fill = false)
				)

				Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					IconButton(onClick = onHomeClick) {
						Icon(
							painter = painterResource(R.drawable.ic_house),
							contentDescription = stringResource(R.string.lbl_home)
						)
					}
					ToolbarClock()
				}
			}

			// Second Row: InfoRow on Left, Action ToolBar on Right
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 40.dp, end = 40.dp, top = 2.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Box(modifier = Modifier.height(20.dp)) {
					selectedItem?.baseItem?.let { baseItem ->
						BaseItemInfoRow(
							item = baseItem,
							mediaSource = null,
							includeRuntime = true
						)
					}
				}

				Row(
					horizontalArrangement = Arrangement.spacedBy(1.dp),
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier.offset(y = (-3).dp)
				) {
					IconButton(
						onClick = { showSortBar = !showSortBar; showFilterBar = false; showAlphaPicker = false },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(R.drawable.ic_sort),
							contentDescription = stringResource(R.string.lbl_sort_by),
							tint = if (showSortBar) JellyfinTheme.colorScheme.buttonActive else Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
					IconButton(
						onClick = { showFilterBar = !showFilterBar; showSortBar = false; showAlphaPicker = false },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(R.drawable.ic_filter),
							contentDescription = stringResource(R.string.filters),
							tint = if (showFilterBar || selectedGenre != null || selectedType != null || selectedAudio != null) JellyfinTheme.colorScheme.buttonActive else Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
					IconButton(
						onClick = { isUnwatchedOnly = !isUnwatchedOnly },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(if (isUnwatchedOnly) R.drawable.ic_watch else R.drawable.ic_unwatch),
							contentDescription = stringResource(R.string.lbl_unwatched),
							tint = if (isUnwatchedOnly) JellyfinTheme.colorScheme.buttonActive else Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
					IconButton(
						onClick = { isFavoritesOnly = !isFavoritesOnly },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(R.drawable.ic_heart),
							contentDescription = stringResource(R.string.lbl_favorite),
							tint = if (isFavoritesOnly) Color.Red else Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
					IconButton(
						onClick = { showAlphaPicker = !showAlphaPicker; showSortBar = false; showFilterBar = false },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(R.drawable.ic_jump_letter),
							contentDescription = stringResource(R.string.lbl_by_letter),
							tint = if (showAlphaPicker || selectedLetter != null) JellyfinTheme.colorScheme.buttonActive else Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
					IconButton(
						onClick = { showSettingsDialog = true },
						contentPadding = PaddingValues(2.dp),
						modifier = Modifier.size(28.dp)
					) {
						Icon(
							painter = painterResource(R.drawable.ic_settings),
							contentDescription = stringResource(R.string.lbl_settings),
							tint = Color.White.copy(alpha = 0.85f),
							modifier = Modifier.size(20.dp)
						)
					}
				}
			}

			// Optional Filter Bar (Audio, Genres, Types)
			if (showFilterBar) {
				FilterPanel(
					availableGenres = availableGenres,
					availableAudio = availableAudio,
					availableTypes = availableTypes,
					selectedGenre = selectedGenre,
					selectedAudio = selectedAudio,
					selectedType = selectedType,
					isMoviesLibrary = folder?.collectionType == CollectionType.MOVIES,
					onSelectGenre = { selectedGenre = it },
					onSelectAudio = { selectedAudio = it },
					onSelectType = { selectedType = it },
					onResetFilters = {
						selectedGenre = null
						selectedAudio = null
						selectedType = null
					}
				)
			}

			// Optional AlphaPicker Bar
			if (showAlphaPicker) {
				val letters = remember { "#ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray() }
				LazyRow(
					contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					itemsIndexed(letters.toList()) { _, char ->
						AlphaPickerButton(
							letter = char,
							selected = selectedLetter == char,
							onClick = {
								selectedLetter = if (selectedLetter == char) null else char
							}
						)
					}
				}
			}

			// Optional Sort Option Bar (All 9 Sort Options)
			if (showSortBar) {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 40.dp, vertical = 6.dp),
					horizontalArrangement = Arrangement.spacedBy(10.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					item {
						IconButton(onClick = { updateSort(ItemSortBy.SORT_NAME, SortOrder.ASCENDING) }) {
							Text(stringResource(R.string.lbl_name), color = if (currentSortBy == ItemSortBy.SORT_NAME) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_date_added), color = if (currentSortBy == ItemSortBy.DATE_CREATED) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_premier_date), color = if (currentSortBy == ItemSortBy.PREMIERE_DATE) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_community_rating), color = if (currentSortBy == ItemSortBy.COMMUNITY_RATING) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.CRITIC_RATING, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_critic_rating), color = if (currentSortBy == ItemSortBy.CRITIC_RATING) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.OFFICIAL_RATING, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_rating), color = if (currentSortBy == ItemSortBy.OFFICIAL_RATING) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING) }) {
							Text(stringResource(R.string.lbl_last_played), color = if (currentSortBy == ItemSortBy.DATE_PLAYED) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.RUNTIME, SortOrder.ASCENDING) }) {
							Text(stringResource(R.string.lbl_runtime), color = if (currentSortBy == ItemSortBy.RUNTIME) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
					item {
						IconButton(onClick = { updateSort(ItemSortBy.RANDOM, SortOrder.ASCENDING) }) {
							Text(stringResource(R.string.random), color = if (currentSortBy == ItemSortBy.RANDOM) JellyfinTheme.colorScheme.buttonActive else Color.White, fontSize = 13.sp)
						}
					}
				}
			}

			Spacer(Modifier.height(12.dp))

			val posterSize = libraryPreferences?.get(LibraryPreferences.posterSize) ?: PosterSize.SMALL

			Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
				if (gridDirection == GridDirection.HORIZONTAL) {
					val horizontalRows = if (imageType == ImageType.BANNER || imageType == ImageType.THUMB) 3 else 2
					LazyHorizontalGrid(
						rows = GridCells.Fixed(horizontalRows),
						contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.spacedBy(16.dp),
						verticalArrangement = Arrangement.spacedBy(16.dp),
						modifier = Modifier.fillMaxSize()
					) {
						itemsIndexed(itemsState, key = { index, item -> "${item.baseItem?.id}_$index" }) { index, item ->
							var isFocused by remember { mutableStateOf(false) }
							val isFirstCard = index == 0

							if (isFirstCard) {
								LaunchedEffect(itemsState.isNotEmpty()) {
									if (itemsState.isNotEmpty() && !hasRequestedFocus) {
										hasRequestedFocus = true
										if (selectedItem == null) {
											selectedItem = itemsState.first()
											onItemFocused(itemsState.first())
										}
										kotlinx.coroutines.delay(100)
										try { firstCardFocusRequester.requestFocus() } catch (e: Exception) {}
									}
								}
							}

							Box(
								modifier = Modifier
									.zIndex(if (isFocused) 1f else 0f)
									.then(if (isFirstCard) Modifier.focusRequester(firstCardFocusRequester) else Modifier)
									.onFocusChanged { focusState ->
										isFocused = focusState.isFocused
										if (focusState.isFocused) {
											selectedItem = item
											selectedIndex = index
											adapterRef.value?.loadMoreItemsIfNeeded(index)
											onItemFocused(item)
										}
									}
									.clickable(
										interactionSource = remember { MutableInteractionSource() },
										indication = null,
										onClick = {
											adapterRef.value?.let { adapter ->
												onItemClick(item, adapter)
											}
										}
									)
							) {
								BaseRowItemCard(
									item = item,
									focused = isFocused,
									showInfo = false,
									imageType = imageType
								)
							}
						}
					}
				} else {
					val gridColumns = getGridColumnCount(imageType, posterSize, gridDirection)

					LazyVerticalGrid(
						columns = gridColumns,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
						verticalArrangement = Arrangement.spacedBy(10.dp),
						contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp),
						modifier = Modifier.fillMaxSize()
					) {
						itemsIndexed(itemsState, key = { index, item -> "${item.baseItem?.id}_$index" }) { index, item ->
							var isFocused by remember { mutableStateOf(false) }
							val isFirstCard = index == 0

							if (isFirstCard) {
								LaunchedEffect(itemsState.isNotEmpty()) {
									if (itemsState.isNotEmpty() && !hasRequestedFocus) {
										hasRequestedFocus = true
										if (selectedItem == null) {
											selectedItem = itemsState.first()
											onItemFocused(itemsState.first())
										}
										kotlinx.coroutines.delay(100)
										try { firstCardFocusRequester.requestFocus() } catch (e: Exception) {}
									}
								}
							}

							Box(
								modifier = Modifier
									.zIndex(if (isFocused) 1f else 0f)
									.then(if (isFirstCard) Modifier.focusRequester(firstCardFocusRequester) else Modifier)
									.onFocusChanged { focusState ->
										isFocused = focusState.isFocused
										if (focusState.isFocused) {
											selectedItem = item
											selectedIndex = index
											adapterRef.value?.loadMoreItemsIfNeeded(index)
											onItemFocused(item)
										}
									}
									.clickable(
										interactionSource = remember { MutableInteractionSource() },
										indication = null,
										onClick = {
											adapterRef.value?.let { adapter ->
												onItemClick(item, adapter)
											}
										}
									)
							) {
								BaseRowItemCard(
									item = item,
									focused = isFocused,
									showInfo = false,
									imageType = imageType,
									fillMaxWidth = true
								)
							}
						}
					}
				}

				if (itemsState.isNotEmpty()) {
					val totalDisplayable = maxOf(adapterRef.value?.totalItems ?: 0, itemsState.size)
					Text(
						text = "${selectedIndex + 1} | $totalDisplayable",
						style = JellyfinTheme.typography.default.copy(
							fontSize = 18.sp,
							fontWeight = FontWeight.Light
						),
						color = Color.White,
						modifier = Modifier
							.align(Alignment.BottomEnd)
							.padding(end = 60.dp, bottom = 16.dp)
					)
				}
			}
		}
	}

	if (showSettingsDialog && folder != null) {
		val itemId = folder.id
		val displayPrefsId = folder.displayPreferencesId ?: folder.id.toString()

		ProvideRouter(
			routes,
			Routes.LIBRARIES_DISPLAY,
			mapOf("itemId" to itemId.toString(), "displayPreferencesId" to displayPrefsId)
		) {
			SettingsDialog(
				visible = showSettingsDialog,
				onDismissRequest = {
					showSettingsDialog = false
				}
			) {
				SettingsRouterContent()
			}
		}
	}
}

package org.jellyfin.androidtv.ui.browsing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.Extras
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.BaseRowItemCard
import org.jellyfin.androidtv.ui.shared.toolbar.Toolbar
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarButtons
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject

abstract class BrowseFolderFragment : Fragment(), RowLoader {
	protected var folder: BaseItemDto? = null
	protected var includeType: String? = null

	protected var rows = mutableListOf<BrowseRowDef>()

	private val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
	}

	private val backgroundService by inject<BackgroundService>()
	private val itemLauncher by inject<ItemLauncher>()
	private val navigationRepository by inject<NavigationRepository>()

	private val rowsState = mutableStateListOf<BrowseRowDef>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val folderJson = arguments?.getString(Extras.Folder)
		if (!folderJson.isNullOrBlank()) {
			runCatching {
				folder = json.decodeFromString<BaseItemDto>(folderJson)
			}.onFailure {
				timber.log.Timber.e(it, "Failed to parse folder DTO in BrowseFolderFragment")
			}
		}
		includeType = arguments?.getString(Extras.IncludeType)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
				setupQueries(this@BrowseFolderFragment)
			}
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
					BrowseFolderScreen(
						title = folder?.name,
						rows = rowsState,
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

	protected abstract suspend fun setupQueries(rowLoader: RowLoader)

	override fun loadRows(rows: MutableList<BrowseRowDef>) {
		rowsState.clear()
		rowsState.addAll(rows)
	}
}

@Composable
fun GridButtonCard(
	item: GridButtonBaseRowItem,
	focused: Boolean,
	modifier: Modifier = Modifier
) {
	val button = item.gridButton
	val backgroundColor = if (focused) {
		JellyfinTheme.colorScheme.buttonFocused
	} else {
		colorResource(R.color.button_default_normal_background)
	}
	val textColor = if (focused) {
		JellyfinTheme.colorScheme.onButtonFocused
	} else {
		colorResource(R.color.button_default_normal_text)
	}

	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier
			.width(140.dp)
			.height(40.dp)
			.clip(RoundedCornerShape(8.dp))
			.background(backgroundColor)
			.padding(horizontal = 14.dp)
	) {
		if (button.imageRes != null) {
			Image(
				painter = painterResource(button.imageRes),
				contentDescription = button.text,
				contentScale = ContentScale.Fit,
				modifier = Modifier.size(20.dp)
			)
		}

		Text(
			text = button.text,
			style = TextStyle(
				color = textColor,
				fontSize = 13.sp,
				fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium
			),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
fun BrowseFolderScreen(
	title: String?,
	rows: List<BrowseRowDef>,
	onItemClick: (BaseRowItem, ItemRowAdapter) -> Unit,
	onItemFocused: (BaseRowItem?) -> Unit,
	onHomeClick: () -> Unit
) {
	val context = LocalContext.current
	var selectedItem by remember { mutableStateOf<BaseRowItem?>(null) }
	val selectedItemFocusRequester = remember { FocusRequester() }
	var hasRequestedFocus by remember { mutableStateOf(false) }

	val listState = rememberLazyListState()
	val coroutineScope = rememberCoroutineScope()
	var focusedRowIndex by remember { mutableIntStateOf(0) }

	val rowData = remember { mutableStateMapOf<Int, List<BaseRowItem>>() }
	val rowAdapters = remember { mutableStateMapOf<Int, ItemRowAdapter>() }

	val lifecycleOwner = LocalLifecycleOwner.current
	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				try { selectedItemFocusRequester.requestFocus() } catch (e: Exception) {}
			}
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.fillMaxSize()) {
			// Top Header Row: Title on TOP LEFT (32sp), Home Button + Clock on TOP RIGHT
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 56.dp, end = 40.dp, top = 20.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = selectedItem?.getCardName(context) ?: title ?: "",
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

			Column(
				modifier = Modifier
					.padding(start = 56.dp, end = 48.dp, top = 4.dp)
					.fillMaxWidth()
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

				Spacer(Modifier.height(12.dp))

				Box(modifier = Modifier.height(150.dp).width(570.dp)) {
					val summary = selectedItem?.getSummary(context) ?: ""
					if (summary.isNotBlank()) {
						Text(
							text = summary,
							style = JellyfinTheme.typography.default.copy(
								fontSize = 16.sp,
								fontWeight = FontWeight.Light
							),
							color = Color(0xFFDDDDDD),
							maxLines = 7,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}

			Spacer(Modifier.height(16.dp))

			LazyColumn(
				state = listState,
				modifier = Modifier
					.fillMaxSize()
					.clipToBounds(),
				verticalArrangement = Arrangement.spacedBy(16.dp),
				contentPadding = PaddingValues(bottom = 64.dp)
			) {
				itemsIndexed(rows, key = { rowIndex, row -> "${row.headerText}_$rowIndex" }) { rowIndex, def ->
					val lifecycle = LocalLifecycleOwner.current.lifecycle
					val itemsState = rowData[rowIndex] ?: emptyList()

					LaunchedEffect(def) {
						if (rowAdapters[rowIndex] == null) {
							val adapter = BrowseRowAdapterFactory.createAdapter(context, def)
							rowAdapters[rowIndex] = adapter
							adapter.setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
								override fun onResponse() {
									val loaded = (0 until adapter.size()).mapNotNull { adapter.get(it) as? BaseRowItem }
									rowData[rowIndex] = loaded
									if (rowIndex == 0 && selectedItem == null && loaded.isNotEmpty()) {
										selectedItem = loaded.first()
										onItemFocused(loaded.first())
									}
								}
							})
							adapter.Retrieve()
						}
					}

					if (itemsState.isNotEmpty()) {
						Column {
							Text(
								text = def.headerText,
								style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
								modifier = Modifier.padding(horizontal = 56.dp, vertical = 6.dp)
							)

							LazyRow(
								contentPadding = PaddingValues(horizontal = 56.dp),
								horizontalArrangement = Arrangement.spacedBy(12.dp)
							) {
								itemsIndexed(itemsState, key = { index, item -> "${rowIndex}_${item.baseItem?.id}_$index" }) { index, item ->
									var isFocused by remember { mutableStateOf(false) }
									val isSelectedItem = (item == selectedItem || (selectedItem == null && rowIndex == 0 && index == 0))

									if (isSelectedItem) {
										LaunchedEffect(itemsState.isNotEmpty()) {
											if (itemsState.isNotEmpty() && !hasRequestedFocus) {
												hasRequestedFocus = true
												if (selectedItem == null) {
													selectedItem = itemsState.first()
													onItemFocused(itemsState.first())
												}
												kotlinx.coroutines.delay(100)
												try { selectedItemFocusRequester.requestFocus() } catch (e: Exception) {}
											}
										}
									}

									Box(
										modifier = Modifier
											.zIndex(if (isFocused) 1f else 0f)
											.then(if (isSelectedItem) Modifier.focusRequester(selectedItemFocusRequester) else Modifier)
											.onFocusChanged { focusState ->
												isFocused = focusState.isFocused
												if (focusState.isFocused) {
													selectedItem = item
													if (focusedRowIndex != rowIndex) {
														focusedRowIndex = rowIndex
														coroutineScope.launch {
															listState.animateScrollToItem(rowIndex)
														}
													}
													if (index >= itemsState.size - 3) {
														rowAdapters[rowIndex]?.loadMoreItemsIfNeeded(index)
													}
													onItemFocused(item)
												}
											}
											.clickable(
												interactionSource = remember { MutableInteractionSource() },
												indication = null,
												onClick = {
													rowAdapters[rowIndex]?.let { adapter ->
														onItemClick(item, adapter)
													}
												}
											)
									) {
										if (item is GridButtonBaseRowItem) {
											GridButtonCard(
												item = item,
												focused = isFocused
											)
										} else {
											BaseRowItemCard(
												item = item,
												focused = isFocused,
												showInfo = false
											)
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}
}

package org.jellyfin.androidtv.ui.itemdetail

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.ui.zIndex
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.sdk.getFullName
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.compose.koinInject
import java.util.UUID

private sealed interface ItemListRow {
	data class DiscHeader(val discNumber: Int) : ItemListRow
	data class Track(val item: BaseItemDto, val index: Int) : ItemListRow
}

@Composable
private fun ExpandableAlbumOverviewText(
	overviewText: String?,
	isMusicAlbum: Boolean,
	modifier: Modifier = Modifier
) {
	var isEllipsized by remember { mutableStateOf(false) }
	var isFocused by remember { mutableStateOf(false) }
	var showDialog by remember { mutableStateOf(false) }

	Box(
		modifier = modifier
			.padding(vertical = 4.dp)
			.then(
				if (isEllipsized) {
					Modifier
						.clip(RoundedCornerShape(6.dp))
						.background(if (isFocused) JellyfinTheme.colorScheme.buttonFocused.copy(alpha = 0.2f) else Color.Transparent)
						.border(
							width = if (isFocused) 2.dp else 0.dp,
							color = if (isFocused) JellyfinTheme.colorScheme.buttonFocused else Color.Transparent,
							shape = RoundedCornerShape(6.dp)
						)
						.padding(horizontal = 8.dp, vertical = 4.dp)
						.onFocusChanged { isFocused = it.isFocused }
						.clickable { showDialog = true }
				} else Modifier
			)
	) {
		Text(
			text = overviewText.orEmpty(),
			style = JellyfinTheme.typography.listCaption,
			color = JellyfinTheme.colorScheme.listCaption,
			minLines = if (isMusicAlbum) 7 else 1,
			maxLines = 7,
			overflow = TextOverflow.Ellipsis,
			onTextLayout = { textLayoutResult ->
				if (textLayoutResult.hasVisualOverflow) {
					isEllipsized = true
				}
			}
		)
	}

	if (showDialog && !overviewText.isNullOrBlank()) {
		val scrollState = rememberScrollState()
		val focusRequester = remember { FocusRequester() }
		val coroutineScope = rememberCoroutineScope()

		LaunchedEffect(Unit) {
			focusRequester.requestFocus()
		}

		Dialog(
			onDismissRequest = { showDialog = false },
			properties = DialogProperties(usePlatformDefaultWidth = false)
		) {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(Color.Black.copy(alpha = 0.85f))
					.clickable(
						interactionSource = remember { MutableInteractionSource() },
						indication = null
					) { showDialog = false },
				contentAlignment = Alignment.Center
			) {
				Box(
					modifier = Modifier
						.width(600.dp)
						.height(400.dp)
						.clip(RoundedCornerShape(12.dp))
						.background(JellyfinTheme.colorScheme.surface)
						.focusRequester(focusRequester)
						.focusable()
						.onPreviewKeyEvent { keyEvent ->
							if (keyEvent.type == KeyEventType.KeyDown) {
								when (keyEvent.nativeKeyEvent.keyCode) {
									KeyEvent.KEYCODE_DPAD_DOWN -> {
										coroutineScope.launch {
											val target = (scrollState.value + 120).coerceAtMost(scrollState.maxValue)
											scrollState.animateScrollTo(target)
										}
										true
									}
									KeyEvent.KEYCODE_DPAD_UP -> {
										coroutineScope.launch {
											val target = (scrollState.value - 120).coerceAtLeast(0)
											scrollState.animateScrollTo(target)
										}
										true
									}
									KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
										showDialog = false
										true
									}
									else -> false
								}
							} else false
						}
						.padding(24.dp)
				) {
					Column(
						modifier = Modifier
							.fillMaxSize()
							.verticalScroll(scrollState)
					) {
						Text(
							text = overviewText,
							style = TextStyle(
								fontSize = 16.sp,
								lineHeight = 22.sp,
								fontWeight = FontWeight.Normal
							),
							color = Color.White
						)
					}
				}

				Row(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(24.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					Icon(
						painter = painterResource(R.drawable.ic_control_select),
						contentDescription = null,
						tint = Color.White,
						modifier = Modifier.size(20.dp)
					)
					Text(
						text = stringResource(R.string.press_to_close),
						style = JellyfinTheme.typography.listCaption,
						color = Color.White
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberPivotBringIntoViewSpec(): BringIntoViewSpec {
	return remember {
		object : BringIntoViewSpec {
			override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
				val topPivot = containerSize * 0.35f
				val bottomPivot = containerSize * 0.65f

				return when {
					offset < topPivot -> offset - topPivot
					offset > bottomPivot -> offset - bottomPivot
					else -> 0f
				}
			}
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemListScreen(
	baseItem: BaseItemDto?,
	items: List<BaseItemDto>,
	currentlyPlayingItemId: UUID?,
	hasAudioQueue: Boolean,
	onPlayAllClick: () -> Unit,
	onShuffleAllClick: () -> Unit,
	onAddToQueueClick: () -> Unit,
	onInstantMixClick: () -> Unit,
	onFavoriteClick: () -> Unit,
	onOpenArtistClick: (() -> Unit)?,
	onItemClick: (item: BaseItemDto, index: Int) -> Unit,
	onHomeClick: (() -> Unit)? = null,
	onItemUpdated: ((index: Int, updatedItem: BaseItemDto) -> Unit)? = null,
	modifier: Modifier = Modifier
) {
	val context = LocalContext.current
	val imageHelper = koinInject<ImageHelper>()
	val listState = rememberLazyListState()
	val pivotBringIntoViewSpec = rememberPivotBringIntoViewSpec()
	val playButtonFocusRequester = remember { FocusRequester() }
	var initialFocusRequested by remember { mutableStateOf(false) }

	val playButtonModifier = Modifier
		.focusRequester(playButtonFocusRequester)
		.onGloballyPositioned {
			if (!initialFocusRequested && baseItem != null) {
				if (playButtonFocusRequester.safeRequestFocus()) {
					initialFocusRequested = true
				}
			}
		}

	val primaryImageUrl = remember(baseItem) {
		if (baseItem != null) {
			imageHelper.getPrimaryImageUrl(baseItem, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
		} else null
	}

	val genresText = remember(baseItem) {
		baseItem?.genres?.joinToString(" / ")
	}

	val isFavorite = baseItem?.userData?.isFavorite == true

	val textMeasurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val headlineStyle = JellyfinTheme.typography.listHeadline
	val captionStyle = JellyfinTheme.typography.listCaption

	val configuration = LocalConfiguration.current

	val displayItems = remember(items) {
		if (items.all { it.indexNumber == null }) {
			items
		} else {
			items.sortedWith(
				compareBy<BaseItemDto> { it.parentIndexNumber ?: 1 }
					.thenBy { it.indexNumber ?: Int.MAX_VALUE }
			)
		}
	}

	val listRowItems = remember(displayItems) {
		val hasMultipleDiscs = displayItems.map { it.parentIndexNumber ?: 1 }.distinct().size > 1
		if (!hasMultipleDiscs) {
			displayItems.mapIndexed { index, item -> ItemListRow.Track(item, index) }
		} else {
			val rows = mutableListOf<ItemListRow>()
			var currentDiscNumber: Int? = null
			displayItems.forEachIndexed { index, item ->
				val discNum = item.parentIndexNumber ?: 1
				if (currentDiscNumber != discNum) {
					currentDiscNumber = discNum
					rows.add(ItemListRow.DiscHeader(discNum))
				}
				rows.add(ItemListRow.Track(item, index))
			}
			rows
		}
	}

	val dynamicTitleWidth = remember(displayItems, headlineStyle, captionStyle, density, context, configuration) {
		if (displayItems.isEmpty()) return@remember 120.dp

		val boldHeadlineStyle = headlineStyle.copy(fontWeight = FontWeight.Bold)

		var maxPx = 0
		for (item in displayItems) {
			val displayName = if (item.type != BaseItemKind.AUDIO && item.seriesName != null) {
				item.getFullName(context)
			} else {
				item.name
			} ?: ""

			val artistList = item.artists
			val artistName = if (!artistList.isNullOrEmpty()) artistList.first()
			else if (!item.albumArtist.isNullOrBlank()) item.albumArtist
			else null

			val titleWidth = textMeasurer.measure(
				text = displayName,
				style = boldHeadlineStyle,
				maxLines = 1,
				softWrap = false
			).size.width
			val artistWidth = if (!artistName.isNullOrBlank()) {
				textMeasurer.measure(
					text = artistName,
					style = captionStyle,
					maxLines = 1,
					softWrap = false
				).size.width
			} else 0

			val itemMax = maxOf(titleWidth, artistWidth)
			if (itemMax > maxPx) {
				maxPx = itemMax
			}
		}

		val maxDp = with(density) { maxPx.toDp() }
		val screenWidthDp = configuration.screenWidthDp.dp
		val maxAvailableWidth = (screenWidthDp * 0.5f).coerceAtLeast(120.dp)

		(maxDp + 8.dp).coerceIn(120.dp, maxAvailableWidth)
	}

	val dynamicDurationWidth = remember(displayItems, headlineStyle, density) {
		if (displayItems.isEmpty()) return@remember 48.dp

		var maxPx = 0
		for (item in displayItems) {
			val formattedRunTime = TimeUtils.formatMillis((item.runTimeTicks ?: 0L) / 10000L)
			val w = textMeasurer.measure(formattedRunTime, style = headlineStyle).size.width
			if (w > maxPx) {
				maxPx = w
			}
		}

		val maxDp = with(density) { maxPx.toDp() }
		(maxDp + 4.dp).coerceAtLeast(40.dp)
	}

	LaunchedEffect(baseItem) {
		if (baseItem != null) {
			initialFocusRequested = false
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		// Top-Right Header with Home Button and Clock
		Row(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(top = 20.dp, end = 40.dp)
				.zIndex(10f),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			if (onHomeClick != null) {
				IconButton(
					onClick = onHomeClick,
					modifier = Modifier
						.focusProperties {
							down = playButtonFocusRequester
						}
						.onPreviewKeyEvent { keyEvent ->
							if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
								playButtonFocusRequester.safeRequestFocus()
								true
							} else {
								false
							}
						}
				) {
					Icon(
						painter = painterResource(id = R.drawable.ic_house),
						contentDescription = stringResource(id = R.string.lbl_home)
					)
				}
			}
			ToolbarClock()
		}

		CompositionLocalProvider(LocalBringIntoViewSpec provides pivotBringIntoViewSpec) {
			LazyColumn(
				state = listState,
				modifier = Modifier
					.fillMaxSize()
					.clipToBounds()
					.focusGroup()
					.focusRestorer(),
				contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 64.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp)
			) {
				// Header Row
				item {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(bottom = 16.dp),
						horizontalArrangement = Arrangement.spacedBy(32.dp),
						verticalAlignment = Alignment.Top
					) {
						// Poster Image
						Box(
							modifier = Modifier
								.size(180.dp)
								.clip(RoundedCornerShape(8.dp))
								.background(JellyfinTheme.colorScheme.surface),
							contentAlignment = Alignment.Center
						) {
							if (!primaryImageUrl.isNullOrBlank()) {
								AsyncImage(
									model = primaryImageUrl,
									contentDescription = baseItem?.name,
									modifier = Modifier.fillMaxSize(),
									contentScale = ContentScale.Crop
								)
							} else {
								Icon(
									painter = painterResource(id = R.drawable.ic_album),
									contentDescription = null,
									modifier = Modifier.size(64.dp),
									tint = JellyfinTheme.colorScheme.onBackground
								)
							}
						}

						// Title, Genres, Summary & Buttons
						Column(
							modifier = Modifier.weight(1f),
							verticalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Text(
								text = baseItem?.name ?: stringResource(id = R.string.loading),
								style = JellyfinTheme.typography.default.copy(
									fontSize = 28.sp,
									lineHeight = 34.sp,
									fontWeight = FontWeight.Bold
								),
								color = JellyfinTheme.colorScheme.onBackground,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis
							)

							if (!genresText.isNullOrBlank()) {
								Text(
									text = genresText,
									style = JellyfinTheme.typography.listCaption,
									color = JellyfinTheme.colorScheme.listCaption,
									maxLines = 1,
									overflow = TextOverflow.Ellipsis
								)
							}

							val overview = baseItem?.overview
							val isMusicAlbum = baseItem == null || baseItem.type == BaseItemKind.MUSIC_ALBUM
							if (isMusicAlbum || !overview.isNullOrBlank()) {
								ExpandableAlbumOverviewText(
									overviewText = overview,
									isMusicAlbum = isMusicAlbum
								)
							}

							// Action Buttons Row
							Row(
								horizontalArrangement = Arrangement.spacedBy(12.dp),
								verticalAlignment = Alignment.CenterVertically,
								modifier = Modifier
									.padding(top = 8.dp)
									.focusGroup()
							) {
								if (hasAudioQueue && baseItem?.type == BaseItemKind.MUSIC_ALBUM) {
									Button(
										onClick = onAddToQueueClick,
										modifier = playButtonModifier
									) {
										Icon(
											painter = painterResource(id = R.drawable.ic_add),
											contentDescription = null
										)
										Spacer(modifier = Modifier.width(8.dp))
										Text(text = stringResource(id = R.string.lbl_add_to_queue))
									}
								} else {
									Button(
										onClick = onPlayAllClick,
										modifier = playButtonModifier
									) {
										Icon(
											painter = painterResource(id = R.drawable.ic_play),
											contentDescription = null
										)
										Spacer(modifier = Modifier.width(8.dp))
										Text(text = stringResource(id = if (baseItem?.isFolder == true) R.string.lbl_play_all else R.string.lbl_play))
									}
								}

								if (baseItem?.isFolder == true) {
									Button(onClick = onShuffleAllClick) {
										Icon(
											painter = painterResource(id = R.drawable.ic_shuffle),
											contentDescription = null
										)
										Spacer(modifier = Modifier.width(8.dp))
										Text(text = stringResource(id = R.string.lbl_shuffle_all))
									}
								}

								if (baseItem?.type == BaseItemKind.MUSIC_ALBUM) {
									IconButton(onClick = onInstantMixClick) {
										Icon(
											painter = painterResource(id = R.drawable.ic_mix),
											contentDescription = stringResource(id = R.string.lbl_instant_mix)
										)
									}
								}

								IconButton(onClick = onFavoriteClick) {
									Icon(
										painter = painterResource(id = R.drawable.ic_heart),
										contentDescription = stringResource(id = R.string.lbl_favorite),
										tint = if (isFavorite) Color.Red else JellyfinTheme.colorScheme.onButton
									)
								}

								if (onOpenArtistClick != null) {
									IconButton(onClick = onOpenArtistClick) {
										Icon(
											painter = painterResource(id = R.drawable.ic_user),
											contentDescription = stringResource(id = R.string.lbl_open_artist)
										)
									}
								}
							}
						}
					}
				}

				// Track List Items
				items(
					items = listRowItems,
					key = { row ->
						when (row) {
							is ItemListRow.DiscHeader -> "disc_header_${row.discNumber}"
							is ItemListRow.Track -> row.item.id
						}
					}
				) { row ->
					when (row) {
						is ItemListRow.DiscHeader -> {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.focusProperties { canFocus = false }
									.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								Text(
									text = stringResource(id = R.string.lbl_disc_number, row.discNumber),
									style = JellyfinTheme.typography.listHeadline.copy(
										fontWeight = FontWeight.Bold,
										fontSize = 16.sp
									),
									color = JellyfinTheme.colorScheme.onBackground
								)
								Spacer(modifier = Modifier.width(16.dp))
								Box(
									modifier = Modifier
										.weight(1f)
										.height(1.dp)
										.background(JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.2f))
								)
							}
						}

						is ItemListRow.Track -> {
							val index = row.index
							val item = row.item
							var isFocused by remember { mutableStateOf(false) }
							val isCurrentlyPlaying = remember(currentlyPlayingItemId, item.id) {
								currentlyPlayingItemId != null && item.id == currentlyPlayingItemId
							}

							val artistName = remember(item) {
								val artistList = item.artists
								if (!artistList.isNullOrEmpty()) artistList.first()
								else if (!item.albumArtist.isNullOrBlank()) item.albumArtist
								else null
							}

							val formattedRunTime = remember(item.runTimeTicks) {
								TimeUtils.formatMillis((item.runTimeTicks ?: 0L) / 10000L)
							}

							val displayName = remember(item) {
								if (item.type != BaseItemKind.AUDIO && item.seriesName != null) {
									item.getFullName(context)
								} else {
									item.name
								}
							}

							val isTrackFavorite = remember(item.userData?.isFavorite) {
								item.userData?.isFavorite == true
							}

							val itemFocusRequester = remember { FocusRequester() }
							var showContextMenu by remember { mutableStateOf(false) }

							Box(
								modifier = Modifier.fillMaxWidth(),
								contentAlignment = Alignment.Center
							) {
								Box(
									modifier = Modifier
										.focusRequester(itemFocusRequester)
										.clip(RoundedCornerShape(6.dp))
										.background(
											when {
												isFocused -> JellyfinTheme.colorScheme.buttonFocused
												isCurrentlyPlaying -> JellyfinTheme.colorScheme.buttonActive
												else -> Color.Transparent
											}
										)
										.onFocusChanged { isFocused = it.isFocused }
										.itemCardClickable(
											onClick = { onItemClick(item, index) },
											onLongClick = { showContextMenu = true }
										)
										.padding(horizontal = 16.dp, vertical = 12.dp)
								) {
									Row(
										verticalAlignment = Alignment.CenterVertically
									) {
										// Index / Playing Icon
										Box(
											modifier = Modifier.size(32.dp),
											contentAlignment = Alignment.Center
										) {
											if (isCurrentlyPlaying) {
												Icon(
													painter = painterResource(id = R.drawable.ic_play),
													contentDescription = null,
													tint = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.onBackground,
													modifier = Modifier.size(20.dp)
												)
											} else {
												val trackNumberText = item.indexNumber?.toString().orEmpty()
												if (trackNumberText.isNotEmpty()) {
													Text(
														text = trackNumberText,
														style = JellyfinTheme.typography.listHeadline,
														color = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.listCaption
													)
												}
											}
										}

										Spacer(modifier = Modifier.width(16.dp))

										// Track Name & Artist
										Column(
											modifier = Modifier.width(dynamicTitleWidth),
											horizontalAlignment = Alignment.Start
										) {
											Text(
												text = displayName ?: "",
												style = JellyfinTheme.typography.listHeadline.copy(
													fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
												),
												color = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.listHeadline,
												maxLines = 1,
												overflow = TextOverflow.Ellipsis,
												softWrap = false,
												modifier = if (isFocused) Modifier.basicMarquee() else Modifier
											)

											if (!artistName.isNullOrBlank()) {
												Text(
													text = artistName,
													style = JellyfinTheme.typography.listCaption,
													color = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.listCaption,
													maxLines = 1,
													overflow = TextOverflow.Ellipsis,
													softWrap = false,
													modifier = if (isFocused) Modifier.basicMarquee() else Modifier
												)
											}
										}

										Spacer(modifier = Modifier.width(16.dp))

										// Track Runtime
										Text(
											text = formattedRunTime,
											style = JellyfinTheme.typography.listHeadline,
											color = if (isFocused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.listCaption,
											modifier = Modifier.width(dynamicDurationWidth)
										)

										Spacer(modifier = Modifier.width(12.dp))

										// Favorite Icon Column (Fixed width)
										Box(
											modifier = Modifier.size(24.dp),
											contentAlignment = Alignment.Center
										) {
											if (isTrackFavorite) {
												Icon(
													painter = painterResource(id = R.drawable.ic_heart),
													contentDescription = stringResource(id = R.string.lbl_favorite),
													tint = Color.Red,
													modifier = Modifier.size(18.dp)
												)
											}
										}
									}
								}

								if (showContextMenu) {
									val rowItem = remember(item) { BaseItemDtoBaseRowItem(item) }
									ItemContextMenu(
										item = rowItem,
										expanded = showContextMenu,
										onDismissRequest = { showContextMenu = false },
										focusRequester = itemFocusRequester,
										onItemUpdated = { updatedItem ->
											updatedItem.baseItem?.let { updatedBaseItem ->
												onItemUpdated?.invoke(index, updatedBaseItem)
											}
										}
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

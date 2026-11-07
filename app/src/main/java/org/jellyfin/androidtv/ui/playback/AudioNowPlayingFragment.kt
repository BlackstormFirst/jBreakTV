package org.jellyfin.androidtv.ui.playback

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.androidtv.ui.presentation.composable.BaseRowItemCard
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import androidx.compose.runtime.CompositionLocalProvider
import org.jellyfin.androidtv.ui.navigation.focus.FocusPersistManager
import org.jellyfin.androidtv.ui.navigation.focus.LocalFocusPersistManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject

class AudioNowPlayingFragment : Fragment() {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val mediaManager = koinInject<MediaManager>()
		val playbackManager = koinInject<PlaybackManager>()
		val backgroundService = koinInject<BackgroundService>()
		val navigationRepository = koinInject<NavigationRepository>()
		val focusPersistManager = remember { FocusPersistManager() }

		JellyfinTheme {
			CompositionLocalProvider(LocalFocusPersistManager provides focusPersistManager) {
				AudioNowPlayingScreen(
					mediaManager = mediaManager,
					playbackManager = playbackManager,
					backgroundService = backgroundService,
					navigationRepository = navigationRepository,
				)
			}
		}
	}
}

@Composable
fun AudioNowPlayingScreen(
	mediaManager: MediaManager,
	playbackManager: PlaybackManager,
	backgroundService: BackgroundService,
	navigationRepository: NavigationRepository,
) {
	val playState by playbackManager.state.playState.collectAsState()
	val isPlaying = playState == PlayState.PLAYING

	val queueEntry by rememberQueueEntry(playbackManager)
	val currentBaseItem = queueEntry?.run { baseItemFlow.collectAsState(baseItem).value }
	val currentItem = currentBaseItem ?: mediaManager.currentAudioItem

	var isRepeatMode by remember { mutableStateOf(mediaManager.isRepeatMode) }
	var isShuffleMode by remember { mutableStateOf(mediaManager.isShuffleMode) }

	val playPauseFocusRequester = remember { FocusRequester() }

	LaunchedEffect(currentItem) {
		if (currentItem != null) {
			backgroundService.setBackground(currentItem, fallbackToSplashscreen = true)
			playPauseFocusRequester.safeRequestFocus()
		} else {
			if (navigationRepository.canGoBack) navigationRepository.goBack()
			else navigationRepository.navigate(Destinations.home)
		}
	}

	Box(
		modifier = Modifier.fillMaxSize()
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
		) {
			// Top Header Row: Title on TOP LEFT, Home Button + Clock on TOP RIGHT
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 56.dp, end = 40.dp, top = 20.dp, bottom = 16.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					text = stringResource(R.string.lbl_now_playing),
					style = JellyfinTheme.typography.default.copy(
						fontSize = 24.sp,
						fontWeight = FontWeight.Bold
					),
					color = JellyfinTheme.colorScheme.onBackground,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f, fill = false)
				)

				Row(
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically
				) {
					IconButton(
						onClick = {
							navigationRepository.reset(Destinations.home, clearHistory = true)
						},
						modifier = Modifier
							.focusProperties {
								down = playPauseFocusRequester
							}
							.onPreviewKeyEvent { keyEvent ->
								if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
									playPauseFocusRequester.safeRequestFocus()
									true
								} else {
									false
								}
							}
					) {
						Icon(
							painter = painterResource(R.drawable.ic_house),
							contentDescription = stringResource(R.string.lbl_home)
						)
					}
					ToolbarClock()
				}
			}

			if (currentItem != null) {
				val positionInfo by rememberPlayerPositionInfo(playbackManager)
				val imageHelper = koinInject<ImageHelper>()
				val albumImageUrl = remember(currentItem) {
					imageHelper.getPrimaryImageUrl(currentItem, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT)
				}

				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 56.dp)
				) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(32.dp),
						verticalAlignment = Alignment.Top
					) {
						// Left side: Track details, seekbar, and action buttons
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = currentItem.artists?.firstOrNull() ?: currentItem.albumArtist ?: "",
								style = JellyfinTheme.typography.listCaption,
								color = JellyfinTheme.colorScheme.listCaption
							)
							Text(
								text = currentItem.name ?: "",
								style = JellyfinTheme.typography.default.copy(
									fontSize = 28.sp,
									lineHeight = 34.sp,
									fontWeight = FontWeight.Bold
								),
								color = JellyfinTheme.colorScheme.onBackground
							)
							if (!currentItem.album.isNullOrBlank()) {
								Text(
									text = stringResource(R.string.lbl_now_playing_album, currentItem.album ?: ""),
									style = JellyfinTheme.typography.listCaption,
									color = JellyfinTheme.colorScheme.listCaption
								)
							}

							Spacer(Modifier.height(16.dp))

							Row(
								modifier = Modifier
									.fillMaxWidth()
									.padding(vertical = 8.dp),
								verticalAlignment = Alignment.CenterVertically,
								horizontalArrangement = Arrangement.spacedBy(12.dp)
							) {
								Text(
									text = TimeUtils.formatMillis(positionInfo.active.inWholeMilliseconds),
									style = JellyfinTheme.typography.listCaption,
									color = JellyfinTheme.colorScheme.listCaption
								)
								PlayerSeekbar(
									playbackManager = playbackManager,
									modifier = Modifier
										.weight(1f)
										.height(6.dp)
								)
								Text(
									text = TimeUtils.formatMillis(positionInfo.duration.inWholeMilliseconds),
									style = JellyfinTheme.typography.listCaption,
									color = JellyfinTheme.colorScheme.listCaption
								)
							}

							Spacer(Modifier.height(8.dp))

							Row(
								horizontalArrangement = Arrangement.spacedBy(12.dp),
								verticalAlignment = Alignment.CenterVertically,
								modifier = Modifier.focusRestorer().focusGroup()
							) {
								IconButton(onClick = { mediaManager.prevAudioItem() }) {
									Icon(painterResource(R.drawable.ic_previous_episode), contentDescription = stringResource(R.string.lbl_prev_item))
								}
								IconButton(onClick = { mediaManager.rewind() }) {
									Icon(painterResource(R.drawable.ic_rewind), contentDescription = stringResource(R.string.rewind))
								}
								IconButton(
									onClick = { mediaManager.togglePlayPause() },
									modifier = Modifier.focusKey("play_pause", focusRequester = playPauseFocusRequester, initialFocus = true)
								) {
									Icon(
										painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
										contentDescription = stringResource(if (isPlaying) R.string.lbl_pause else R.string.lbl_play)
									)
								}
								IconButton(onClick = { mediaManager.fastForward() }) {
									Icon(painterResource(R.drawable.ic_fast_forward), contentDescription = stringResource(R.string.fast_forward))
								}
								IconButton(onClick = { mediaManager.nextAudioItem() }) {
									Icon(painterResource(R.drawable.ic_next), contentDescription = stringResource(R.string.lbl_next_item))
								}
								IconButton(onClick = { isRepeatMode = mediaManager.toggleRepeat() }) {
									Icon(
										painter = painterResource(R.drawable.ic_loop),
										contentDescription = stringResource(R.string.lbl_repeat),
										tint = if (isRepeatMode) JellyfinTheme.colorScheme.buttonActive else Color.White
									)
								}
								IconButton(onClick = {
									mediaManager.shuffleAudioQueue()
									isShuffleMode = mediaManager.isShuffleMode
								}) {
									Icon(
										painter = painterResource(R.drawable.ic_shuffle),
										contentDescription = stringResource(R.string.lbl_shuffle_queue),
										tint = if (isShuffleMode) JellyfinTheme.colorScheme.buttonActive else Color.White
									)
								}
								if (currentItem.albumId != null) {
									IconButton(onClick = {
										navigationRepository.navigate(Destinations.itemList(currentItem.albumId!!))
									}) {
										Icon(painterResource(R.drawable.ic_album), contentDescription = stringResource(R.string.lbl_open_album))
									}
								}
								if (!currentItem.albumArtists.isNullOrEmpty()) {
									IconButton(onClick = {
										navigationRepository.navigate(Destinations.itemDetails(currentItem.albumArtists!!.first().id))
									}) {
										Icon(painterResource(R.drawable.ic_user), contentDescription = stringResource(R.string.lbl_open_artist))
									}
								}
							}
						}

						// Right side: Album cover art
						Box(
							modifier = Modifier
								.size(200.dp)
								.clip(RoundedCornerShape(8.dp))
								.background(JellyfinTheme.colorScheme.surface),
							contentAlignment = Alignment.Center
						) {
							if (!albumImageUrl.isNullOrBlank()) {
								AsyncImage(
									model = albumImageUrl,
									contentDescription = currentItem.album ?: currentItem.name,
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
					}
				}

				// Section Header: Current Queue
				Text(
					text = stringResource(R.string.current_queue),
					style = JellyfinTheme.typography.listHeader.copy(
						color = JellyfinTheme.colorScheme.listHeader
					),
					modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 28.dp, bottom = 12.dp)
				)

				val queueEntries by playbackManager.queue.entries.collectAsState(initial = emptyList())
				val validQueueEntries = remember(queueEntries) {
					queueEntries.filter { it.baseItem != null }
				}
				var showContextMenu by remember { mutableStateOf(false) }
				var selectedQueueItem by remember { mutableStateOf<AudioQueueBaseRowItem?>(null) }

				LazyRow(
					modifier = Modifier
						.fillMaxWidth()
						.focusRestorer()
						.focusGroup(),
					contentPadding = PaddingValues(horizontal = 56.dp, vertical = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(16.dp)
				) {
					itemsIndexed(
						items = validQueueEntries,
						key = { index, entry -> "${entry.baseItem?.id ?: index}_$index" }
					) { index, entry ->
						val rowItem = remember(entry) { AudioQueueBaseRowItem(entry) }
						val interactionSource = remember { MutableInteractionSource() }
						val isFocusedState by interactionSource.collectIsFocusedAsState()
						var isFocusedInternal by remember { mutableStateOf(false) }
						val isFocused = isFocusedState || isFocusedInternal
						val cardFocusRequester = remember { FocusRequester() }

						Box(
							modifier = Modifier
								.focusRequester(cardFocusRequester)
								.zIndex(if (isFocused) 10f else 1f)
								.onFocusChanged { focusState ->
									isFocusedInternal = focusState.isFocused
								}
								.itemCardClickable(
									interactionSource = interactionSource,
									onClick = {
										mediaManager.playFrom(entry)
									},
									onLongClick = {
										selectedQueueItem = rowItem
										showContextMenu = true
									}
								)
						) {
							BaseRowItemCard(
								item = rowItem,
								focused = isFocused
							)
						}
					}
				}

				if (showContextMenu && selectedQueueItem != null) {
					ItemContextMenu(
						item = selectedQueueItem,
						expanded = showContextMenu,
						onDismissRequest = {
							showContextMenu = false
							selectedQueueItem = null
						}
					)
				}
			}
		}
	}
}

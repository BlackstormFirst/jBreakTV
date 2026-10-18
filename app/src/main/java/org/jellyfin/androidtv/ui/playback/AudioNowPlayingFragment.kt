package org.jellyfin.androidtv.ui.playback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.content
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.BaseRowItemCard
import org.jellyfin.androidtv.ui.shared.toolbar.Toolbar
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarButtons
import org.jellyfin.androidtv.ui.shared.toolbar.ToolbarClock
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
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
		val keyProcessor = koinInject<KeyProcessor>()

		JellyfinTheme {
			AudioNowPlayingScreen(
				mediaManager = mediaManager,
				playbackManager = playbackManager,
				backgroundService = backgroundService,
				navigationRepository = navigationRepository,
				keyProcessor = keyProcessor,
				activity = requireActivity()
			)
		}
	}
}

@Composable
fun AudioNowPlayingScreen(
	mediaManager: MediaManager,
	playbackManager: PlaybackManager,
	backgroundService: BackgroundService,
	navigationRepository: NavigationRepository,
	keyProcessor: KeyProcessor,
	activity: FragmentActivity
) {
	val context = LocalContext.current

	val currentItem = mediaManager.currentAudioItem
	val isPlaying = mediaManager.isPlayingAudio

	LaunchedEffect(currentItem) {
		if (currentItem != null) {
			backgroundService.setBackground(currentItem)
		} else {
			if (navigationRepository.canGoBack) navigationRepository.goBack()
			else navigationRepository.navigate(Destinations.home)
		}
	}

	val playPauseFocusRequester = remember { FocusRequester() }

	Box(
		modifier = Modifier.fillMaxSize()
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
		) {
			Toolbar(
				start = {
					ToolbarButtons {
						IconButton(onClick = {
							navigationRepository.reset(Destinations.home, clearHistory = true)
						}) {
							Icon(
								painter = painterResource(R.drawable.ic_house),
								contentDescription = stringResource(R.string.lbl_home)
							)
						}
					}
				},
				center = {
					Text(
						text = stringResource(R.string.lbl_now_playing),
						style = JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Bold),
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				},
				end = {
					ToolbarClock()
				}
			)

			if (currentItem != null) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(horizontal = 48.dp)
				) {
					Text(
						text = currentItem.artists?.firstOrNull() ?: currentItem.albumArtist ?: "",
						style = JellyfinTheme.typography.default.copy(fontWeight = FontWeight.Medium),
						color = Color.White
					)
					Text(
						text = currentItem.name ?: "",
						style = JellyfinTheme.typography.listHeader,
						fontWeight = FontWeight.Bold,
						color = Color.White
					)
					if (!currentItem.album.isNullOrBlank()) {
						Text(
							text = stringResource(R.string.lbl_now_playing_album, currentItem.album ?: ""),
							style = JellyfinTheme.typography.listCaption,
							color = Color.LightGray
						)
					}

					Spacer(Modifier.height(16.dp))

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
							modifier = Modifier.focusRequester(playPauseFocusRequester)
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
						IconButton(onClick = { mediaManager.toggleRepeat() }) {
							Icon(
								painter = painterResource(R.drawable.ic_loop),
								contentDescription = stringResource(R.string.lbl_repeat),
								tint = if (mediaManager.isRepeatMode) JellyfinTheme.colorScheme.buttonActive else Color.White
							)
						}
						IconButton(onClick = { mediaManager.shuffleAudioQueue() }) {
							Icon(
								painter = painterResource(R.drawable.ic_shuffle),
								contentDescription = stringResource(R.string.lbl_shuffle_queue),
								tint = if (mediaManager.isShuffleMode) JellyfinTheme.colorScheme.buttonActive else Color.White
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

					Spacer(Modifier.height(24.dp))

					Text(
						text = stringResource(R.string.current_queue),
						style = JellyfinTheme.typography.listHeader,
						modifier = Modifier.padding(vertical = 8.dp)
					)

					val queueEntries by playbackManager.queue.entries.collectAsState(initial = emptyList())

					LazyRow(
						modifier = Modifier.focusRestorer().focusGroup(),
						horizontalArrangement = Arrangement.spacedBy(16.dp),
						contentPadding = PaddingValues(bottom = 64.dp)
					) {
						itemsIndexed(queueEntries, key = { index, entry -> entry.baseItem?.id ?: index }) { index, entry ->
							val rowItem = remember(entry) { AudioQueueBaseRowItem(entry) }
							var isFocused by remember { mutableStateOf(false) }

							Box(
								modifier = Modifier
									.zIndex(if (isFocused) 1f else 0f)
									.onFocusChanged { focusState ->
										isFocused = focusState.isFocused
									}
									.clickable {
										keyProcessor.createItemMenu(
											rowItem,
											rowItem.baseItem?.userData,
											activity
										)
									}
							) {
								BaseRowItemCard(
									item = rowItem,
									focused = isFocused
								)
							}
						}
					}
				}
			}
		}
	}
}

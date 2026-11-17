package org.jellyfin.androidtv.ui.itemdetail

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.showAddToQueueToast
import org.jellyfin.androidtv.util.sdk.TrailerUtils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class FullDetailsFragment : Fragment() {

    private val viewModel by viewModel<FullDetailsViewModel>()
    private val playbackHelper by inject<PlaybackHelper>()
    private val itemLauncher by inject<ItemLauncher>()
    private val navigationRepository by inject<NavigationRepository>()
    private val mediaManager by inject<MediaManager>()
    private val api by inject<ApiClient>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val itemIdStr = arguments?.getString("ItemId")
        val itemId = Utils.uuidOrNull(itemIdStr)

        if (itemId != null) {
            viewModel.loadItem(requireContext(), itemId)
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.uiState.collectAsState()

                JellyfinTheme {
                    VideoDetailsScreen(
                        state = state,
                        onPlayClick = {
                            state.item?.let { item ->
                                val targetId = state.selectedMediaSource?.id?.let { Utils.uuidOrNull(it) } ?: item.id
                                val startPos = if (item.isFolder == true) null else 0L
                                playbackHelper.retrieveAndPlay(targetId, false, requireContext(), startPos)
                            }
                        },
                        onResumeClick = {
                            state.item?.let { item ->
                                val targetId = state.selectedMediaSource?.id?.let { Utils.uuidOrNull(it) } ?: item.id
                                playbackHelper.retrieveAndPlay(targetId, false, requireContext())
                            }
                        },
                        onSelectVersion = { mediaSource ->
                            viewModel.selectMediaSource(requireContext(), mediaSource)
                        },
                        onFavoriteClick = {
                            viewModel.toggleFavorite()
                        },
                        onPlayedClick = {
                            viewModel.togglePlayed()
                        },
                        onRefreshItem = {
                            viewModel.refresh()
                        },
                        onTrailerClick = {
                            state.item?.let { item ->
                                val localTrailerCount = item.localTrailerCount ?: 0
                                if (localTrailerCount < 1) {
                                    val intent = TrailerUtils.getExternalTrailerIntent(requireContext(), item)
                                    if (intent != null) {
                                        startActivity(intent)
                                    } else {
                                        Toast.makeText(requireContext(), R.string.no_player_message, Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    lifecycleScope.launch {
                                        runCatching {
                                            val trailers = withContext(Dispatchers.IO) {
                                                api.userLibraryApi.getLocalTrailers(item.id).content
                                            }
                                            val trailerItem = trailers.firstOrNull()
                                            if (trailerItem != null) {
                                                playbackHelper.retrieveAndPlay(trailerItem.id, false, requireContext())
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onQueueClick = {
                            state.item?.let { item ->
                                mediaManager.queueAudioItem(item)
                                showAddToQueueToast(requireContext(), item)
                            }
                        },
                        onShuffleClick = {
                            state.item?.let { item ->
                                playbackHelper.retrieveAndPlay(item.id, true, requireContext())
                            }
                        },
                        onPreviousEpisodeClick = { prevId ->
                            navigationRepository.navigate(Destinations.itemDetails(prevId), true)
                        },
                        onGoToSeriesClick = {
                            state.item?.seriesId?.let { seriesId ->
                                navigationRepository.navigate(Destinations.itemDetails(seriesId))
                            }
                        },
                        onDeleteClick = {
                            viewModel.deleteCurrentItem {
                                if (navigationRepository.canGoBack) navigationRepository.goBack()
                                else navigationRepository.navigate(Destinations.home)
                            }
                        },
                        onHomeClick = {
                            navigationRepository.reset(Destinations.home, clearHistory = true)
                        },
                        onItemClick = { rowItem ->
                            itemLauncher.launch(rowItem, null, requireContext())
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.subscribeWebSockets()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val itemIdStr = arguments?.getString("ItemId")
        val itemId = Utils.uuidOrNull(itemIdStr)
        if (itemId != null) {
            viewModel.onResume(requireContext(), itemId)
        }
    }
}

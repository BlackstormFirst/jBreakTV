package org.jellyfin.androidtv.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class HomeFragment : Fragment() {
    private val sessionRepository by inject<SessionRepository>()
    private val serverRepository by inject<ServerRepository>()
    private val notificationRepository by inject<NotificationsRepository>()
    private val viewModel by activityViewModel<HomeViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = content {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val focusTrigger by viewModel.focusRequestTrigger.collectAsStateWithLifecycle()

        JellyfinTheme {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    MainToolbar(MainToolbarActiveButton.Home)

                    Box(
                        modifier = Modifier
                            .focusGroup()
                            .focusRestorer()
                            .weight(1f)
                    ) {
                        when (val state = uiState) {
                            is HomeUiState.Success -> {
                                HomeComposeScreen(
                                    rows = state.rows,
                                    initialFocusedItemKey = viewModel.lastFocusedItemKey,
                                    initialFocusedRowIndex = viewModel.lastFocusedRowIndex,
                                    focusTrigger = focusTrigger,
                                    onItemClick = { item, rowIndex ->
                                        viewModel.onItemClicked(item, rowIndex)
                                    },
                                    onItemFocus = { item, rowIndex ->
                                        viewModel.onItemSelected(item, rowIndex)
                                    }
                                )
                            }
                            is HomeUiState.Loading, is HomeUiState.Error -> {
                                // Loading or empty state
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionRepository.currentSession
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .map { session ->
                if (session == null) null
                else serverRepository.getServer(session.serverId)
            }
            .onEach { server ->
                notificationRepository.updateServerNotifications(server)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}

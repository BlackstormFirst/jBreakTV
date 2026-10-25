package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.repository.AuthenticationRepository
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.ServerUserRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.MarkdownRenderer
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class ServerFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ID = "server_id"
	}

	private val startupViewModel: StartupViewModel by activityViewModel()
	private val markdownRenderer: MarkdownRenderer by inject()
	private val authenticationRepository: AuthenticationRepository by inject()
	private val serverUserRepository: ServerUserRepository by inject()
	private val backgroundService: BackgroundService by inject()

	private val serverIdArgument get() = arguments?.getString(ARG_SERVER_ID)?.ifBlank { null }?.toUUIDOrNull()

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val server = serverIdArgument?.let(startupViewModel::getServer)

		if (server == null) {
			navigateFragment<SelectServerFragment>(keepHistory = false)
			return@content
		}

		val users by startupViewModel.users.collectAsStateWithLifecycle()

		JellyfinTheme {
			ServerScreen(
				server = server,
				users = users,
				lastUserId = startupViewModel.lastUserId,
				getUserImage = { user -> startupViewModel.getUserImage(server, user) },
				onUserClick = { user ->
					startupViewModel.authenticate(server, user).onEach { state ->
						when (state) {
							AuthenticatingState, AuthenticatedState -> Unit
							RequireSignInState -> navigateFragment<UserLoginFragment>(createBundle {
								putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
								putString(UserLoginFragment.ARG_USERNAME, user.name)
							})
							ServerUnavailableState, is ApiClientErrorLoginState -> Toast.makeText(context, R.string.server_connection_failed, Toast.LENGTH_LONG).show()
							is ServerVersionNotSupported -> Toast.makeText(
								context,
								getString(R.string.server_issue_outdated_version, state.server.version, ServerRepository.recommendedServerVersion.toString()),
								Toast.LENGTH_LONG
							).show()
						}
					}.launchIn(lifecycleScope)
				},
				onUserLogout = { user -> authenticationRepository.logout(user) },
				onUserRemove = { user ->
					if (user is PrivateUser) {
						serverUserRepository.deleteStoredUser(user)
						startupViewModel.loadUsers(server)
					}
				},
				onAddUser = {
					navigateFragment<UserLoginFragment>(createBundle {
						putString(UserLoginFragment.ARG_SERVER_ID, server.id.toString())
						putString(UserLoginFragment.ARG_USERNAME, null)
					})
				},
				onEditServer = { navigateFragment<SelectServerFragment>() },
				openHelp = { navigateFragment<ConnectHelpAlertFragment>() },
				disclaimerText = server.loginDisclaimer?.let { markdownRenderer.toMarkdownSpanned(it) }
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// Request focus on root view so ComposeView receives Android Window focus
		view.post {
			view.requestFocus()
		}

		val server = serverIdArgument?.let(startupViewModel::getServer)
		if (server != null) {
			startupViewModel.loadUsers(server)

			viewLifecycleOwner.lifecycleScope.launch {
				viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
					val updated = startupViewModel.updateServer(server)
					if (updated) {
						startupViewModel.getServer(server.id)
					}
				}
			}
		}
	}

	private inline fun <reified F : Fragment> navigateFragment(
		args: Bundle = createBundle(),
		keepHistory: Boolean = true,
	) {
		requireActivity()
			.supportFragmentManager
			.commit {
				replace<F>(R.id.content_view, null, args)
				if (keepHistory) addToBackStack(null)
			}
	}

	override fun onResume() {
		super.onResume()

		startupViewModel.reloadStoredServers()
		backgroundService.clearBackgrounds()

		val server = serverIdArgument?.let(startupViewModel::getServer)
		if (server != null) startupViewModel.loadUsers(server)
		else navigateFragment<SelectServerFragment>()
	}
}


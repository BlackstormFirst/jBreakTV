package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
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
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.UnavailableQuickConnectState
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.startup.UserLoginViewModel
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class UserLoginFragment : Fragment() {
	companion object {
		const val ARG_USERNAME = "user_name"
		const val ARG_SERVER_ID = "server_id"
		const val ARG_SKIP_QUICKCONNECT = "skip_quickconnect"
	}

	private val userLoginViewModel: UserLoginViewModel by activityViewModel()
	private val backgroundService: BackgroundService by inject()

	private val usernameArgument get() = arguments?.getString(ARG_USERNAME)?.ifBlank { null }
	private val serverIdArgument get() = arguments?.getString(ARG_SERVER_ID)?.ifBlank { null }
	private val skipQuickConnect get() = arguments?.getBoolean(ARG_SKIP_QUICKCONNECT) ?: false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		userLoginViewModel.forcedUsername = usernameArgument
		userLoginViewModel.setServer(serverIdArgument?.toUUIDOrNull())
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val server by userLoginViewModel.server.collectAsStateWithLifecycle()
		val quickConnectState by userLoginViewModel.quickConnectState.collectAsStateWithLifecycle()
		val loginState by userLoginViewModel.loginState.collectAsStateWithLifecycle()

		JellyfinTheme {
			UserLoginScreen(
				server = server,
				forcedUsername = userLoginViewModel.forcedUsername,
				skipQuickConnect = skipQuickConnect,
				quickConnectState = quickConnectState,
				loginState = loginState,
				onLoginWithCredentials = { username, password ->
					lifecycleScope.launch {
						userLoginViewModel.login(username, password)
					}
				},
				onCancel = { parentFragmentManager.popBackStack() }
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		userLoginViewModel.clearLoginState()

		lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				userLoginViewModel.initiateQuickconnect()

				// Background image handling
				userLoginViewModel.server.onEach { server ->
					if (server != null) backgroundService.setBackground(server)
					else backgroundService.clearBackgrounds()
				}.launchIn(this)
			}
		}
	}
}

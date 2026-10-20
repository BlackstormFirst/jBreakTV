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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.startup.ServerAddViewModel
import org.jellyfin.androidtv.util.createBundle
import org.koin.androidx.viewmodel.ext.android.viewModel

class ServerAddFragment : Fragment() {
	companion object {
		const val ARG_SERVER_ADDRESS = "server_address"
	}

	private val startupViewModel: ServerAddViewModel by viewModel()

	private val serverAddressArgument get() = arguments?.getString(ARG_SERVER_ADDRESS)?.ifBlank { null }

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) = content {
		val state by startupViewModel.state.collectAsStateWithLifecycle()

		JellyfinTheme {
			ServerAddScreen(
				state = state,
				initialAddress = serverAddressArgument,
				onSubmit = { address -> startupViewModel.addServer(address) }
			)
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		if (serverAddressArgument != null) {
			startupViewModel.addServer(serverAddressArgument!!)
		}

		startupViewModel.state.onEach { state ->
			if (state is ConnectedState) {
				parentFragmentManager.commit {
					// Open server view
					replace<StartupToolbarFragment>(R.id.content_view)
					add<ServerFragment>(
						R.id.content_view,
						null,
						createBundle {
							putString(ServerFragment.ARG_SERVER_ID, state.id.toString())
						}
					)
				}
			}
		}.launchIn(lifecycleScope)
	}
}

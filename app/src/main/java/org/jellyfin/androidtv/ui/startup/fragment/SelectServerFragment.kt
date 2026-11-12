package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.auth.model.ConnectingState
import org.jellyfin.androidtv.auth.model.UnableToConnectState
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.ui.ServerButtonView
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.shared.toolbar.StartupToolbar
import org.jellyfin.androidtv.ui.startup.StartupViewModel
import org.jellyfin.androidtv.util.ConnectionErrorUtils
import org.jellyfin.androidtv.util.createBundle
import org.jellyfin.androidtv.util.getSummary
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.compose.koinInject

class SelectServerFragment : Fragment() {
	private val startupViewModel: StartupViewModel by activityViewModel()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		return ComposeView(requireContext()).apply {
			setContent {
				JellyfinTheme {
					SelectServerScreen(
						openHelp = {
							parentFragmentManager.commit {
								addToBackStack(null)
								replace<ConnectHelpAlertFragment>(R.id.content_view)
							}
						}
					)
				}
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		view.post { view.requestFocus() }
	}

	override fun onResume() {
		super.onResume()
		startupViewModel.reloadStoredServers()
		startupViewModel.loadDiscoveryServers()
	}

	@Composable
	fun SelectServerScreen(
		openHelp: () -> Unit = {}
	) {
		val storedServers by startupViewModel.storedServers.collectAsState(initial = emptyList())
		val discoveredServers by startupViewModel.discoveredServers.collectAsState(initial = emptyList())
		val context = LocalContext.current

		val firstServerFocusRequester = remember { FocusRequester() }
		val manualConnectFocusRequester = remember { FocusRequester() }
		var hasRequestedFocus by remember(storedServers.isEmpty()) { mutableStateOf(false) }

		Column(modifier = Modifier.fillMaxSize()) {
			StartupToolbar(openHelp = openHelp)

			Row(
				modifier = Modifier
					.fillMaxSize()
					.padding(horizontal = 48.dp, vertical = 16.dp)
			) {
				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
				) {
					if (storedServers.isEmpty()) {
						Text(
							text = stringResource(id = R.string.welcome_title),
							style = JellyfinTheme.typography.listHeader,
							color = JellyfinTheme.colorScheme.onBackground
						)
						Spacer(modifier = Modifier.height(16.dp))
						Text(
							text = stringResource(id = R.string.welcome_content),
							color = JellyfinTheme.colorScheme.onBackground
						)
					} else {
						Text(
							text = stringResource(id = R.string.saved_servers),
							style = JellyfinTheme.typography.listHeader,
							color = JellyfinTheme.colorScheme.onBackground
						)
						Spacer(modifier = Modifier.height(16.dp))
						LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							itemsIndexed(storedServers) { index, server ->
								AndroidView(
									factory = { ctx ->
										ServerButtonView(ctx).apply {
											name = server.name
											address = server.address
											version = server.version
											setOnClickListener {
												requireActivity().supportFragmentManager.commit {
													replace<ServerFragment>(
														R.id.content_view,
														null,
														createBundle { putString(ServerFragment.ARG_SERVER_ID, server.id.toString()) }
													)
													addToBackStack(null)
												}
											}
											setPopupMenu {
												item(ctx.getString(R.string.lbl_remove)) {
													startupViewModel.deleteServer(server.id)
												}
											}
										}
									},
									update = { view ->
										view.name = server.name
										view.address = server.address
										view.version = server.version
									},
									modifier = Modifier
										.fillMaxWidth()
										.then(
											if (index == 0) {
												Modifier
													.focusRequester(firstServerFocusRequester)
													.onGloballyPositioned {
														if (!hasRequestedFocus) {
															if (firstServerFocusRequester.safeRequestFocus()) {
																hasRequestedFocus = true
															}
														}
													}
											} else Modifier
										)
								)
							}
						}
					}
				}

				Spacer(modifier = Modifier.width(32.dp))

				Column(
					modifier = Modifier
						.weight(1f)
						.fillMaxHeight()
				) {
					Button(
						onClick = {
							parentFragmentManager.commit {
								addToBackStack(null)
								replace<ServerAddFragment>(R.id.content_view)
							}
						},
						modifier = Modifier
							.fillMaxWidth()
							.then(
								if (storedServers.isEmpty()) {
									Modifier
										.focusRequester(manualConnectFocusRequester)
										.onGloballyPositioned {
											if (!hasRequestedFocus) {
												if (manualConnectFocusRequester.safeRequestFocus()) {
													hasRequestedFocus = true
												}
											}
										}
								} else Modifier
							)
					) {
						Icon(imageVector = Icons.Default.Add, contentDescription = null)
						Spacer(modifier = Modifier.width(8.dp))
						Text(text = stringResource(id = R.string.connect_manually_by_address))
					}

					Spacer(modifier = Modifier.height(24.dp))

					Text(
						text = stringResource(id = R.string.discovered_servers_title),
						style = JellyfinTheme.typography.listHeader,
						color = JellyfinTheme.colorScheme.onBackground
					)
					Spacer(modifier = Modifier.height(16.dp))

					var discoveredServerStates by remember { mutableStateOf(mapOf<String, org.jellyfin.androidtv.auth.model.ServerAdditionState>()) }

					if (discoveredServers.isEmpty()) {
						Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
							Text(
								text = stringResource(id = R.string.discovered_servers_empty),
								color = JellyfinTheme.colorScheme.onBackground
							)
						}
					} else {
						LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
							items(discoveredServers) { server ->
								val state = discoveredServerStates[server.id.toString()]
								AndroidView(
									factory = { ctx ->
										ServerButtonView(ctx).apply {
											name = server.name
											address = server.address
											version = server.version
											setOnClickListener {
												startupViewModel.addServer(server.address).onEach { newState ->
													discoveredServerStates = discoveredServerStates + (server.id.toString() to newState)
													if (newState is ConnectedState) {
														parentFragmentManager.commit {
															replace<ServerFragment>(
																R.id.content_view,
																null,
																createBundle { putString(ServerFragment.ARG_SERVER_ID, newState.id.toString()) }
															)
														}
													} else if (newState is UnableToConnectState) {
														val allIssues = newState.addressCandidates.values.flatten()
														val errors = allIssues.flatMap { ConnectionErrorUtils.extractErrorsFromIssue(it, context) }
														val details = ConnectionErrorUtils.formatErrorDetails(errors, context)
														val toastMessage = if (details.isNotEmpty()) {
															"${context.getString(R.string.server_connection_failed)}\n$details"
														} else {
															context.getString(R.string.server_connection_failed)
														}
														Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
													}
												}.launchIn(lifecycleScope)
											}
										}
									},
									update = { view ->
										view.name = server.name
										view.address = server.address
										view.version = server.version
										view.state = when (state) {
											is ConnectingState -> ServerButtonView.State.CONNECTING
											is UnableToConnectState -> ServerButtonView.State.ERROR
											else -> ServerButtonView.State.DEFAULT
										}
									},
									modifier = Modifier.fillMaxWidth()
								)
							}
						}
					}

					Spacer(modifier = Modifier.height(24.dp))
					NotificationsArea()

					Spacer(modifier = Modifier.weight(1f))
					Text(
						text = "jBreakTV ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}",
						color = JellyfinTheme.colorScheme.onBackground.copy(alpha = 0.6f)
					)
				}
			}
		}
	}

	@Composable
	fun NotificationsArea() {
		val notificationsRepository = koinInject<NotificationsRepository>()
		val notifications by notificationsRepository.notifications.collectAsState()

		Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
			for (notification in notifications) {
				if (!notification.public) continue

				Box(
					modifier = Modifier
						.fillMaxWidth()
						.clip(JellyfinTheme.shapes.medium)
						.background(colorResource(id = R.color.lb_basic_card_info_bg_color)),
				) {
					Text(
						text = notification.message,
						modifier = Modifier.padding(10.dp),
						color = colorResource(id = R.color.white),
					)
				}
			}
		}
	}
}

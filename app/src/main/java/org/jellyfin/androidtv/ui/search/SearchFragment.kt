package org.jellyfin.androidtv.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import org.jellyfin.androidtv.ui.navigation.focus.safeRequestFocus
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.item.ItemContextMenu
import org.jellyfin.androidtv.ui.composable.modifier.itemCardClickable
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.presentation.composable.BaseRowItemCard
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class SearchFragment : Fragment() {
	companion object {
		const val EXTRA_QUERY = "query"
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		val viewModel = koinViewModel<SearchViewModel>()
		val backgroundService = koinInject<BackgroundService>()
		val itemLauncher = koinInject<ItemLauncher>()
		val context = LocalContext.current

		var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
		val textInputFocusRequester = remember { FocusRequester() }
		val resultFocusRequester = remember { FocusRequester() }
		val speechRecognizerAvailability = rememberSpeechRecognizerAvailability()

		val searchResultGroups by viewModel.searchResultsFlow.collectAsState(initial = emptyList())

		LaunchedEffect(Unit) {
			val extraQuery = arguments?.getString(EXTRA_QUERY)
			if (!extraQuery.isNullOrBlank()) {
				query = query.copy(text = extraQuery)
				viewModel.searchImmediately(extraQuery)
				resultFocusRequester.safeRequestFocus()
			} else {
				textInputFocusRequester.safeRequestFocus()
			}
		}

		Column(modifier = Modifier.fillMaxSize()) {
			MainToolbar(MainToolbarActiveButton.Search)

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.focusRestorer()
					.focusGroup()
					.padding(horizontal = 48.dp)
			) {
				if (speechRecognizerAvailability) {
					SearchVoiceInput(
						onQueryChange = { query = query.copy(text = it) },
						onQuerySubmit = {
							viewModel.searchImmediately(query.text)
							resultFocusRequester.safeRequestFocus()
						}
					)
				}

				SearchTextInput(
					query = query.text,
					onQueryChange = {
						query = query.copy(text = it)
						viewModel.searchDebounced(query.text)
					},
					onQuerySubmit = {
						viewModel.searchImmediately(query.text)
						// Note: We MUST change the focus to somewhere else when the keyboard is submitted because some vendors (like Amazon)
						// will otherwise just keep showing a (fullscreen) keyboard, soft-locking the app.
						resultFocusRequester.safeRequestFocus()
					},
					modifier = Modifier
						.weight(1f)
						.focusRequester(textInputFocusRequester),
				)
			}

			if (searchResultGroups.isNotEmpty()) {
				LazyColumn(
					modifier = Modifier
						.focusGroup()
						.focusRequester(resultFocusRequester)
						.padding(top = 16.dp)
						.fillMaxSize(),
					verticalArrangement = Arrangement.spacedBy(24.dp),
					contentPadding = PaddingValues(bottom = 64.dp)
				) {
					items(searchResultGroups.toList(), key = { it.labelRes }) { group ->
						Column {
							Text(
								text = stringResource(group.labelRes),
								style = JellyfinTheme.typography.listHeader.copy(color = JellyfinTheme.colorScheme.listHeader),
								modifier = Modifier.padding(start = 56.dp, end = 56.dp, top = 6.dp, bottom = 12.dp)
							)

							LazyRow(
								modifier = Modifier.focusRestorer(),
								contentPadding = PaddingValues(horizontal = 56.dp),
								horizontalArrangement = Arrangement.spacedBy(12.dp)
							) {
								items(group.items.toList(), key = { it.id }) { baseItem ->
									val cardFocusRequester = remember { FocusRequester() }
									val rowItem = remember(baseItem) { BaseItemDtoBaseRowItem(baseItem) }
									val interactionSource = remember { MutableInteractionSource() }
									val isFocusedState by interactionSource.collectIsFocusedAsState()
									var isFocusedInternal by remember { mutableStateOf(false) }
									val isFocused = isFocusedState || isFocusedInternal
									var showContextMenu by remember { mutableStateOf(false) }

									Box(
										modifier = Modifier
											.focusRequester(cardFocusRequester)
											.zIndex(if (isFocused) 1f else 0f)
											.onFocusChanged { focusState ->
												isFocusedInternal = focusState.isFocused
												if (focusState.isFocused) {
													backgroundService.setBackground(baseItem)
												}
											}
											.itemCardClickable(
												interactionSource = interactionSource,
												onClick = {
													itemLauncher.launch(rowItem, null, context)
												},
												onLongClick = {
													showContextMenu = true
												}
											)
									) {
										BaseRowItemCard(
											item = rowItem,
											focused = isFocused
										)

										if (showContextMenu) {
											ItemContextMenu(
												item = rowItem,
												expanded = showContextMenu,
												onDismissRequest = { showContextMenu = false },
												focusRequester = cardFocusRequester,
												onItemUpdated = { _ ->
													viewModel.searchImmediately(query.text)
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
	}
}

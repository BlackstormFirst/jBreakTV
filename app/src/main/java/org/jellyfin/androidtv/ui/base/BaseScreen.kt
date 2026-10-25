package org.jellyfin.androidtv.ui.base

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned

@Composable
@Stable
fun BaseScreen(
	content: @Composable () -> Unit,
) {
	// Work around a focus issue with our current fragment based navigation approach
	// by always focusing the screen contents whenever the screen is created
	val focusRequester = remember { FocusRequester() }
	var hasRequestedFocus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

	Box(
		modifier = Modifier
			.focusRequester(focusRequester)
			.focusGroup()
			.onGloballyPositioned {
				if (!hasRequestedFocus) {
					hasRequestedFocus = true
					runCatching { focusRequester.requestFocus() }
				}
			}
	) {
		content()
	}
}

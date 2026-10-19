package org.jellyfin.androidtv.ui.composable.modifier

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.itemCardClickable(
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
): Modifier {
	return this
		.onKeyEvent { keyEvent ->
			val nativeEvent = keyEvent.nativeKeyEvent
			val keyCode = nativeEvent.keyCode

			if (nativeEvent.action == KeyEvent.ACTION_UP &&
				(keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_INFO)
			) {
				if (onLongClick != null) {
					onLongClick()
					true
				} else {
					false
				}
			} else {
				false
			}
		}
		.combinedClickable(
			interactionSource = remember { MutableInteractionSource() },
			indication = null,
			onClick = onClick,
			onLongClick = onLongClick
		)
}

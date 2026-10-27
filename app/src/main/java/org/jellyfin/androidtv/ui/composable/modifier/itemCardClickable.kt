package org.jellyfin.androidtv.ui.composable.modifier

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.itemCardClickable(
	interactionSource: MutableInteractionSource? = null,
	onClick: () -> Unit,
	onLongClick: (() -> Unit)? = null,
): Modifier {
	val source = interactionSource ?: remember { MutableInteractionSource() }
	var isLongPressHandled by remember { mutableStateOf(false) }

	return this
		.onFocusChanged {
			isLongPressHandled = false
		}
		.onKeyEvent { keyEvent ->
			val nativeEvent = keyEvent.nativeKeyEvent
			val keyCode = nativeEvent.keyCode

			val isCenterKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
				keyCode == KeyEvent.KEYCODE_ENTER ||
				keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

			val isMenuKey = keyCode == KeyEvent.KEYCODE_MENU ||
				keyCode == KeyEvent.KEYCODE_BUTTON_Y ||
				keyCode == KeyEvent.KEYCODE_INFO

			when {
				isMenuKey -> {
					if (nativeEvent.action == KeyEvent.ACTION_UP) {
						if (onLongClick != null) {
							isLongPressHandled = false
							onLongClick()
							true
						} else {
							false
						}
					} else {
						false
					}
				}
				isCenterKey -> {
					if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
						if (nativeEvent.repeatCount > 0 || nativeEvent.isLongPress) {
							if (!isLongPressHandled && onLongClick != null) {
								isLongPressHandled = true
								onLongClick()
								true
							} else {
								isLongPressHandled
							}
						} else {
							isLongPressHandled = false
							false
						}
					} else if (nativeEvent.action == KeyEvent.ACTION_UP) {
						if (isLongPressHandled) {
							isLongPressHandled = false
							true
						} else {
							false
						}
					} else {
						false
					}
				}
				else -> false
			}
		}
		.combinedClickable(
			interactionSource = source,
			indication = null,
			onClick = {
				if (!isLongPressHandled) {
					onClick()
				} else {
					isLongPressHandled = false
				}
			},
			onLongClick = null
		)
}

package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button

class ExpandableTextView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

	private var textContent by mutableStateOf("")
	private var maxLinesVal by mutableIntStateOf(3)

	var text: CharSequence?
		get() = textContent
		set(value) {
			textContent = value?.toString() ?: ""
		}

	var maxLines: Int
		get() = maxLinesVal
		set(value) {
			maxLinesVal = value
		}

	@Composable
	override fun Content() {
		JellyfinTheme {
			var showDialog by remember { mutableStateOf(false) }

			Button(
				onClick = { showDialog = true },
			) {
				Text(
					text = textContent,
					maxLines = maxLinesVal,
					overflow = TextOverflow.Ellipsis
				)
			}

			if (showDialog) {
				Dialog(
					onDismissRequest = { showDialog = false },
					properties = DialogProperties(usePlatformDefaultWidth = false)
				) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color.Black.copy(alpha = 0.8f))
							.clickable(
								interactionSource = remember { MutableInteractionSource() },
								indication = null
							) {
								showDialog = false
							}
							.padding(40.dp)
					) {
						Text(
							text = textContent,
							modifier = Modifier
								.verticalScroll(rememberScrollState())
								.padding(16.dp),
						)
					}
				}
			}
		}
	}
}

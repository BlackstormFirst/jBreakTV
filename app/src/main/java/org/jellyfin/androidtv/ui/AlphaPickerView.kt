package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button

class AlphaPickerView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

	var onAlphaSelected: (letter: Char) -> Unit = {}
	private val letters = "#${context.getString(R.string.byletter_letters)}"

	@Composable
	override fun Content() {
		JellyfinTheme {
			LazyRow {
				items(letters.toList()) { letter ->
					Button(
						onClick = { onAlphaSelected(letter) },
						modifier = Modifier.padding(end = 4.dp)
					) {
						Text(text = letter.toString())
					}
				}
			}
		}
	}

	fun focus(letter: Char) {
		// Focus management in Compose
	}
}

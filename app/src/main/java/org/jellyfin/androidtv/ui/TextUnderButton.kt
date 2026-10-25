package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton

class TextUnderButton @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

	private var labelState by mutableStateOf<String?>(null)
	private var iconResState by mutableIntStateOf(0)
	private var paddingValState by mutableIntStateOf(0)
	private var clickListener: OnClickListener? = null

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
	}

	fun setLabel(text: String?) {
		this.labelState = text
	}

	fun setIcon(@DrawableRes resource: Int, maxHeight: Int? = null) {
		this.iconResState = resource
	}

	fun setPadding(padding: Int?) {
		this.paddingValState = padding ?: 0
	}

	override fun setOnClickListener(l: OnClickListener?) {
		super.setOnClickListener(l)
		clickListener = l
	}

	@Composable
	override fun Content() {
		JellyfinTheme {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				modifier = Modifier.padding(paddingValState.dp)
			) {
				if (iconResState != 0) {
					IconButton(
						onClick = { clickListener?.onClick(this@TextUnderButton) }
					) {
						Icon(painter = painterResource(id = iconResState), contentDescription = labelState)
					}
				}

				if (labelState != null) {
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = labelState!!,
						textAlign = TextAlign.Center,
					)
				}
			}
		}
	}

	companion object {
		@JvmStatic
		@Suppress("LongParameterList")
		fun create(
			context: Context,
			@DrawableRes icon: Int,
			maxHeight: Int? = null,
			padding: Int? = null,
			label: String? = null,
			onClickListener: OnClickListener
		) = TextUnderButton(context).apply {
			setLabel(label)
			setIcon(icon, maxHeight)
			setPadding(padding)
			setOnClickListener(onClickListener)
		}
	}
}

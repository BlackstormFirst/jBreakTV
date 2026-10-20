package org.jellyfin.androidtv.ui.presentation

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import org.jellyfin.androidtv.ui.base.Text

class TextItemPresenter : Presenter() {

	private class ComposeViewWrapper(composeView: ComposeView) : FrameLayout(composeView.context) {
		init {
			isFocusable = true
			isFocusableInTouchMode = true
			descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
			addView(composeView)
		}

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			if (isAttachedToWindow) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
			else setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
		}
	}

	inner class ViewHolder(
		private val composeView: ComposeView,
	) : Presenter.ViewHolder(ComposeViewWrapper(composeView)) {
		var text by mutableStateOf("")

		init {
			composeView.setContent {
				Box(
					modifier = Modifier.size(width = 200.dp, height = 100.dp),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = text,
						color = Color.White
					)
				}
			}
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		return ViewHolder(ComposeView(parent.context))
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return
		viewHolder.text = item?.toString() ?: ""
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) {
		if (viewHolder is ViewHolder) {
			viewHolder.text = ""
		}
	}
}

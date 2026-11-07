package org.jellyfin.androidtv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
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
				val scrollState = rememberScrollState()
				val focusRequester = remember { FocusRequester() }
				val coroutineScope = rememberCoroutineScope()

				LaunchedEffect(Unit) {
					focusRequester.requestFocus()
				}

				Dialog(
					onDismissRequest = { showDialog = false },
					properties = DialogProperties(usePlatformDefaultWidth = false)
				) {
					Box(
						modifier = Modifier
							.fillMaxSize()
							.background(Color.Black.copy(alpha = 0.85f))
							.clickable(
								interactionSource = remember { MutableInteractionSource() },
								indication = null
							) {
								showDialog = false
							},
						contentAlignment = Alignment.Center
					) {
						Box(
							modifier = Modifier
								.width(600.dp)
								.height(400.dp)
								.clip(RoundedCornerShape(12.dp))
								.background(JellyfinTheme.colorScheme.surface)
								.focusRequester(focusRequester)
								.focusable()
								.onPreviewKeyEvent { keyEvent ->
									if (keyEvent.type == KeyEventType.KeyDown) {
										when (keyEvent.nativeKeyEvent.keyCode) {
											KeyEvent.KEYCODE_DPAD_DOWN -> {
												coroutineScope.launch {
													val target = (scrollState.value + 120).coerceAtMost(scrollState.maxValue)
													scrollState.animateScrollTo(target)
												}
												true
											}
											KeyEvent.KEYCODE_DPAD_UP -> {
												coroutineScope.launch {
													val target = (scrollState.value - 120).coerceAtLeast(0)
													scrollState.animateScrollTo(target)
												}
												true
											}
											KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
												showDialog = false
												true
											}
											else -> false
										}
									} else false
								}
								.padding(24.dp)
						) {
							Column(
								modifier = Modifier
									.fillMaxSize()
									.verticalScroll(scrollState)
							) {
								Text(
									text = textContent,
									style = TextStyle(
										fontSize = 16.sp,
										lineHeight = 22.sp,
										fontWeight = FontWeight.Normal,
										color = Color.White
									)
								)
							}
						}
					}
				}
			}
		}
	}
}

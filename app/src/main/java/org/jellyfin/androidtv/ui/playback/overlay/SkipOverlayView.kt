package org.jellyfin.androidtv.ui.playback.overlay

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import java.util.function.Consumer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SkipOverlayComposable(
	visible: Boolean,
	onSkip: () -> Unit = {},
	onFocusPlayer: () -> Unit = {},
	onOpenOsd: () -> Unit = {},
	focusRequester: FocusRequester = remember { FocusRequester() },
) {
	var isFocused by remember { mutableStateOf(false) }

	Box(
		contentAlignment = Alignment.BottomEnd,
		modifier = Modifier
			.fillMaxSize()
			.padding(end = 48.dp, bottom = 120.dp)
	) {
		AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
			val backgroundColor = if (isFocused) {
				colorResource(R.color.popup_menu_background).copy(alpha = 0.95f)
			} else {
				colorResource(R.color.popup_menu_background).copy(alpha = 0.6f)
			}

			Row(
				modifier = Modifier
					.clip(RoundedCornerShape(6.dp))
					.background(backgroundColor)
					.clickable { onSkip() }
					.onFocusChanged { isFocused = it.isFocused }
					.onKeyEvent { keyEvent ->
						if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

						when (keyEvent.key) {
							Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
								onSkip()
								true
							}
							Key.DirectionLeft, Key.MediaRewind -> {
								onFocusPlayer()
								true
							}
							Key.DirectionRight, Key.MediaFastForward -> {
								true
							}
							Key.DirectionUp, Key.DirectionDown -> {
								onOpenOsd()
								true
							}
							Key.Back -> {
								onFocusPlayer()
								true
							}
							else -> false
						}
					}
					.focusRequester(focusRequester)
					.focusable()
					.padding(horizontal = 14.dp, vertical = 10.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_control_select),
					contentDescription = null,
				)

				Text(
					text = stringResource(R.string.segment_action_skip),
					color = Color.White,
					fontSize = 18.sp,
				)
			}
		}
	}
}

class SkipOverlayView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyle: Int = 0
) : AbstractComposeView(context, attrs, defStyle) {
	private val _currentPosition = MutableStateFlow(Duration.ZERO)
	private val _targetPosition = MutableStateFlow<Duration?>(null)
	private val _skipUiEnabled = MutableStateFlow(true)
	private val _requestSkipFocusTrigger = MutableStateFlow(0)
	private val _timerResetTrigger = MutableStateFlow(0)

	var onSkipRequested: Runnable? = null
	var onFocusPlayerRequested: Runnable? = null
	var onOpenOsdRequested: Runnable? = null
	var onVisibilityChangedListener: Consumer<Boolean>? = null

	fun focusSkipButton() {
		_requestSkipFocusTrigger.value += 1
	}

	fun resetAutoHideTimer() {
		_timerResetTrigger.value += 1
	}

	var currentPosition: Duration
		get() = _currentPosition.value
		set(value) {
			_currentPosition.value = value
		}

	var currentPositionMs: Long
		get() = _currentPosition.value.inWholeMilliseconds
		set(value) {
			_currentPosition.value = value.milliseconds
		}

	var targetPosition: Duration?
		get() = _targetPosition.value
		set(value) {
			_targetPosition.value = value
		}

	var targetPositionMs: Long?
		get() = _targetPosition.value?.inWholeMilliseconds
		set(value) {
			_targetPosition.value = value?.milliseconds
		}

	var skipUiEnabled: Boolean
		get() = _skipUiEnabled.value
		set(value) {
			_skipUiEnabled.value = value
		}

	val visible: Boolean
		get() {
			val enabled = _skipUiEnabled.value
			val targetPosition = _targetPosition.value
			val currentPosition = _currentPosition.value

			return enabled && targetPosition != null && currentPosition <= (targetPosition - MediaSegmentRepository.SkipMinDuration)
		}

	@Composable
	override fun Content() {
		val skipUiEnabled by _skipUiEnabled.collectAsState()
		val currentPosition by _currentPosition.collectAsState()
		val targetPosition by _targetPosition.collectAsState()
		val focusTrigger by _requestSkipFocusTrigger.collectAsState()
		val timerResetTrigger by _timerResetTrigger.collectAsState()

		val focusRequester = remember { FocusRequester() }

		val visible by remember(skipUiEnabled, currentPosition, targetPosition) {
			derivedStateOf { visible }
		}

		LaunchedEffect(visible, targetPosition) {
			if (visible && targetPosition != null) {
				onVisibilityChangedListener?.accept(true)
			} else if (!visible) {
				onVisibilityChangedListener?.accept(false)
			}
		}

		LaunchedEffect(focusTrigger) {
			if (focusTrigger > 0 && visible) {
				runCatching { focusRequester.requestFocus() }
			}
		}

		// Auto hide timer (resets whenever targetPosition, skipUiEnabled, or timerResetTrigger changes)
		LaunchedEffect(skipUiEnabled, targetPosition, timerResetTrigger) {
			if (targetPosition != null && skipUiEnabled) {
				delay(MediaSegmentRepository.AskToSkipAutoHideDuration)
				_targetPosition.value = null
			}
		}

		SkipOverlayComposable(
			visible = visible,
			onSkip = { onSkipRequested?.run() },
			onFocusPlayer = { onFocusPlayerRequested?.run() },
			onOpenOsd = { onOpenOsdRequested?.run() },
			focusRequester = focusRequester,
		)
	}
}

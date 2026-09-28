package org.jellyfin.androidtv.ui.base

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlin.time.Duration
import kotlin.time.times

@Immutable
data class SeekbarColors(
	val backgroundColor: Color,
	val bufferColor: Color,
	val progressColor: Color,
	val knobColor: Color,
)

object SeekbarDefaults {
	@ReadOnlyComposable
	@Composable
	fun colors(
		backgroundColor: Color = JellyfinTheme.colorScheme.rangeControlBackground,
		bufferColor: Color = JellyfinTheme.colorScheme.seekbarBuffer,
		progressColor: Color = JellyfinTheme.colorScheme.rangeControlFill,
		knobColor: Color = JellyfinTheme.colorScheme.rangeControlKnob,
	) = SeekbarColors(
		backgroundColor = backgroundColor,
		bufferColor = bufferColor,
		progressColor = progressColor,
		knobColor = knobColor,
	)
}

@Composable
fun Seekbar(
	modifier: Modifier = Modifier,
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
	progress: Duration = Duration.ZERO,
	buffer: Duration = Duration.ZERO,
	duration: Duration = Duration.ZERO,
	seekForwardAmount: Duration = duration / 100,
	seekRewindAmount: Duration = duration / 100,
	onScrubbing: ((scrubbing: Boolean) -> Unit)? = null,
	onScrubbingPreview: ((progress: Duration) -> Unit)? = null,
	onSeek: ((progress: Duration) -> Unit)? = null,
	enabled: Boolean = true,
	colors: SeekbarColors = SeekbarDefaults.colors(),
) {
	val durationMs = duration.inWholeMilliseconds.toFloat().coerceAtLeast(1f)
	val progressPercentage = progress.inWholeMilliseconds.toFloat() / durationMs
	val bufferPercentage = buffer.inWholeMilliseconds.toFloat() / durationMs
	val seekForwardPercentage = seekForwardAmount.inWholeMilliseconds.toFloat() / durationMs
	val seekRewindPercentage = seekRewindAmount.inWholeMilliseconds.toFloat() / durationMs

	Seekbar(
		modifier = modifier,
		interactionSource = interactionSource,
		progress = progressPercentage,
		buffer = bufferPercentage,
		seekForwardAmount = seekForwardPercentage,
		seekRewindAmount = seekRewindPercentage,
		onScrubbing = onScrubbing,
		onScrubbingPreview = if (onScrubbingPreview == null) null else { p -> onScrubbingPreview(p.toDouble() * duration) },
		onSeek = if (onSeek == null) null else { p -> onSeek(p.toDouble() * duration) },
		enabled = enabled,
		colors = colors,
	)
}

@Composable
fun Seekbar(
	modifier: Modifier = Modifier,
	interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
	progress: Float = 0f,
	buffer: Float = 0f,
	seekForwardAmount: Float = 0.01f,
	seekRewindAmount: Float = 0.01f,
	onScrubbing: ((scrubbing: Boolean) -> Unit)? = null,
	onScrubbingPreview: ((progress: Float) -> Unit)? = null,
	onSeek: ((progress: Float) -> Unit)? = null,
	enabled: Boolean = true,
	colors: SeekbarColors = SeekbarDefaults.colors(),
) {
	val focused by interactionSource.collectIsFocusedAsState()
	var progressOverride by remember { mutableStateOf<Float?>(null) }
	val visibleProgress = progressOverride ?: progress
	val knobAlpha by animateFloatAsState(if (focused) 1f else 0f)

	LaunchedEffect(focused) {
		if (!focused && progressOverride != null) {
			progressOverride = null
			onScrubbing?.invoke(false)
		}
	}

	Box(
		modifier = modifier
			.onKeyEvent { keyEvent ->
				if (!enabled) return@onKeyEvent false

				val isForward = keyEvent.key == Key.DirectionRight
				val isRewind = keyEvent.key == Key.DirectionLeft
				val isConfirm = keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
				val isBack = keyEvent.key == Key.Back || keyEvent.key == Key.Escape
				val isKeyDown = keyEvent.type == KeyEventType.KeyDown

				if (isKeyDown) {
					if (isForward) {
						onScrubbing?.invoke(true)
						val current = progressOverride ?: progress
						progressOverride = (current + seekForwardAmount).coerceAtMost(1f)
						onScrubbingPreview?.invoke(progressOverride!!)
						return@onKeyEvent true
					}
					if (isRewind) {
						onScrubbing?.invoke(true)
						val current = progressOverride ?: progress
						progressOverride = (current - seekRewindAmount).coerceAtLeast(0f)
						onScrubbingPreview?.invoke(progressOverride!!)
						return@onKeyEvent true
					}
					if (isConfirm && progressOverride != null) {
						val targetProgress = progressOverride!!
						progressOverride = null
						onScrubbing?.invoke(false)
						onSeek?.invoke(targetProgress)
						return@onKeyEvent true
					}
					if (isBack && progressOverride != null) {
						progressOverride = null
						onScrubbing?.invoke(false)
						return@onKeyEvent true
					}
				}

				return@onKeyEvent false
			}
			.focusable(interactionSource = interactionSource, enabled = enabled)
			.drawWithContent {
				val barCornerRadius = CornerRadius(size.minDimension, size.minDimension)

				// Background bar
				drawRoundRect(
					color = colors.backgroundColor,
					cornerRadius = barCornerRadius,
				)

				// Buffer bar
				if (buffer > 0f) {
					drawRoundRect(
						color = colors.bufferColor,
						size = size.copy(
							width = buffer * size.width,
						),
						cornerRadius = barCornerRadius,
					)
				}

				// Progress bar
				if (visibleProgress > 0f) {
					drawRoundRect(
						color = colors.progressColor,
						size = size.copy(
							width = visibleProgress * size.width,
						),
						cornerRadius = barCornerRadius,
					)
				}

				// Progress knob
				drawCircle(
					color = colors.knobColor,
					alpha = knobAlpha,
					center = center.copy(
						x = visibleProgress * size.width,
					),
					radius = size.minDimension * 2,
				)
			}
	)
}

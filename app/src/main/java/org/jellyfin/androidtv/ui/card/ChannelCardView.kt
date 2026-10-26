package org.jellyfin.androidtv.ui.card

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Seekbar
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Duration
import java.time.LocalDateTime

@Composable
fun ChannelCard(
	channel: BaseItemDto?,
	focused: Boolean,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current

	val nameText = when {
		channel?.number != null -> "${channel.number} ${channel.name.orEmpty()}"
		else -> channel?.name.orEmpty()
	}

	val program = channel?.currentProgram
	val programName = program?.name ?: stringResource(R.string.no_program_data)

	val startDate = program?.startDate
	val endDate = program?.endDate

	val timeText = if ((startDate != null) && (endDate != null)) {
		val formatter = context.getTimeFormatter()
		"${formatter.format(startDate)}-${formatter.format(endDate)}"
	} else {
		""
	}

	val progressPercentage = if ((startDate != null) && (endDate != null)) {
		val now = LocalDateTime.now()
		if (startDate.isBefore(now) && endDate.isAfter(now)) {
			val duration = Duration.between(startDate, endDate)
			val progress = Duration.between(startDate, now)
			if (duration.seconds > 0) {
				(progress.seconds.toFloat() / duration.seconds.toFloat()).coerceIn(0f, 1f)
			} else 0f
		} else 0f
	} else 0f

	val animatedScale by animateFloatAsState(
		targetValue = if (focused) 1.1f else 1.0f,
		animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
		label = "ChannelCardScale",
	)

	val focusMarqueeModifier = if (focused) Modifier.basicMarquee(
		iterations = Int.MAX_VALUE,
		initialDelayMillis = 1200,
	) else Modifier

	val cardShape = JellyfinTheme.shapes.medium
	val cardBgColor = if (focused) JellyfinTheme.colorScheme.buttonFocused else JellyfinTheme.colorScheme.button
	val textColor = if (focused) JellyfinTheme.colorScheme.onButtonFocused else JellyfinTheme.colorScheme.onButton

	Box(
		modifier = modifier
			.graphicsLayer(
				scaleX = animatedScale,
				scaleY = animatedScale,
			)
			.width(200.dp)
			.clip(cardShape)
			.background(cardBgColor, cardShape)
			.padding(14.dp),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier.fillMaxWidth(),
		) {
			Text(
				text = nameText,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				softWrap = false,
				color = textColor,
				style = JellyfinTheme.typography.listHeader,
				modifier = Modifier
					.fillMaxWidth()
					.then(focusMarqueeModifier),
			)

			Spacer(modifier = Modifier.height(2.dp))

			Text(
				text = programName,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				softWrap = false,
				color = textColor.copy(alpha = 0.85f),
				style = JellyfinTheme.typography.listHeadline,
				modifier = Modifier
					.fillMaxWidth()
					.then(focusMarqueeModifier),
			)

			if (timeText.isNotEmpty()) {
				Spacer(modifier = Modifier.height(2.dp))

				Text(
					text = timeText,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					softWrap = false,
					color = textColor.copy(alpha = 0.65f),
					style = JellyfinTheme.typography.listCaption,
					modifier = Modifier.fillMaxWidth(),
				)
			}

			if (program != null) {
				Spacer(modifier = Modifier.height(6.dp))

				Seekbar(
					progress = progressPercentage,
					enabled = false,
					colors = SeekbarDefaults.colors(
						backgroundColor = textColor.copy(alpha = 0.2f),
						progressColor = JellyfinTheme.colorScheme.rangeControlFill,
						knobColor = Color.Transparent,
					),
					modifier = Modifier
						.fillMaxWidth()
						.height(4.dp),
				)
			}
		}
	}
}

class ChannelCardView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
	var channel by mutableStateOf<BaseItemDto?>(null)
	var isCardFocused by mutableStateOf(value = false)

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
			isCardFocused = hasFocus
		}
	}

	fun setItem(channel: BaseItemDto?) {
		this.channel = channel
	}

	@Composable
	override fun Content() {
		ChannelCard(
			channel = channel,
			focused = isCardFocused,
		)
	}
}

package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.design.Tokens

@Composable
@Stable
fun ItemPreview(
	card: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	title: (@Composable () -> Unit)? = null,
	subtitle: (@Composable () -> Unit)? = null,
	spacing: Dp = Tokens.Space.spaceXs,
) {
	ItemPreviewLayout(
		card = card,
		metadata = {
			ItemPreviewMetadata(
				title = title,
				subtitle = subtitle,
				spacing = spacing,
			)
		},
		spacing = spacing,
		modifier = modifier
	)
}

@Composable
@Stable
private fun ItemPreviewMetadata(
	title: (@Composable () -> Unit)?,
	subtitle: (@Composable () -> Unit)?,
	spacing: Dp,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(spacing),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(spacing),
	) {
		title?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorGrey100,
					fontSize = 12.sp,
				),
				content = content,
			)
		}

		subtitle?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorGrey300,
					fontSize = 10.sp,
				),
				content = content,
			)
		}
	}
}

private enum class ItemPreviewSlot { Card, Metadata }

@Composable
@Stable
fun ItemPreviewLayout(
	card: @Composable () -> Unit,
	metadata: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	spacing: Dp = Tokens.Space.spaceXs,
) {
	SubcomposeLayout(modifier = modifier) { constraints ->
		val cardPlaceables = subcompose(ItemPreviewSlot.Card, card).map { it.measure(constraints) }
		val cardWidth = cardPlaceables.maxOfOrNull { it.width } ?: 0
		val cardHeight = cardPlaceables.maxOfOrNull { it.height } ?: 0

		val spacingPx = spacing.roundToPx()

		val metadataConstraints = constraints.copy(
			minWidth = cardWidth,
			maxWidth = cardWidth,
		)
		val metadataPlaceables = subcompose(ItemPreviewSlot.Metadata, metadata).map { it.measure(metadataConstraints) }
		val metadataHeight = metadataPlaceables.maxOfOrNull { it.height } ?: 0

		val totalHeight = cardHeight + (if (metadataHeight > 0) spacingPx + metadataHeight else 0)

		layout(cardWidth, totalHeight) {
			cardPlaceables.forEach { it.placeRelative(0, 0) }
			metadataPlaceables.forEach { it.placeRelative(0, cardHeight + spacingPx) }
		}
	}
}

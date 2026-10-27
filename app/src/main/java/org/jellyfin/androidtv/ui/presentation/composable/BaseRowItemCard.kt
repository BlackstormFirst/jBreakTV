package org.jellyfin.androidtv.ui.presentation.composable

import android.widget.ImageView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemCardBaseItemOverlay
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.androidtv.preference.UserPreferences
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.util.usbdevices.USB_TILE_UUID
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.ui.presentation.config.getDisplayConfig

@Composable
fun BaseRowItemCard(
	item: BaseRowItem?,
	focused: Boolean,
	modifier: Modifier = Modifier,
	showInfo: Boolean = true,
	imageType: ImageType = ImageType.POSTER,
	staticHeight: Int = 150,
	uniformAspect: Boolean = false,
	fillMaxWidth: Boolean = false,
) {
	val userPreferences = org.koin.compose.koinInject<UserPreferences>()
	val api = org.koin.compose.koinInject<ApiClient>()
	val displayCirclePersonCards = remember(userPreferences) {
		userPreferences[UserPreferences.displayCirclePersonCards]
	}

	Box(modifier = modifier.zIndex(if (focused) 1f else 0f)) {
		CardViewHolderContent(
			item = item,
			focused = focused,
			showInfo = showInfo,
			imageType = imageType,
			staticHeight = staticHeight,
			uniformAspect = uniformAspect,
			fillMaxWidth = fillMaxWidth,
			displayCirclePersonCards = displayCirclePersonCards,
			api = api,
		)
	}
}

@Composable
@Stable
private fun CardViewHolderContent(
	item: BaseRowItem?,
	focused: Boolean,
	showInfo: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	uniformAspect: Boolean,
	fillMaxWidth: Boolean = false,
	displayCirclePersonCards: Boolean,
	api: ApiClient,
) {
	val context = LocalContext.current
	val localDensity = LocalDensity.current

	val title = remember(item, context) { item?.getCardName(context) }
	val subtitle = remember(item, context) { item?.getSubText(context) }
	val displayConfig = remember(item, imageType, uniformAspect, displayCirclePersonCards) { item?.getDisplayConfig(imageType, uniformAspect, displayCirclePersonCards) }
	if (item == null || displayConfig == null) return

	val image = displayConfig.image
	val aspectRatio = displayConfig.aspectRatio.takeIf { it >= 0.1f }
		?: image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

	val size = when {
		fillMaxWidth -> DpSize(200.dp, (200f / aspectRatio).dp)
		item.staticHeight -> DpSize(staticHeight.dp * aspectRatio, staticHeight.dp)
		imageType == ImageType.BANNER -> DpSize(240.dp, 60.dp)
		imageType == ImageType.THUMB -> DpSize(180.dp, 100.dp)
		aspectRatio > 1f -> DpSize(130.dp * aspectRatio, 130.dp)
		else -> DpSize(150.dp * aspectRatio, 150.dp)
	}

	val cardSizeModifier = if (fillMaxWidth) {
		Modifier
			.fillMaxWidth()
			.aspectRatio(aspectRatio)
	} else {
		Modifier.size(size)
	}

	val usePreview = displayConfig.overrideShowInfo ?: showInfo

	// Determine if the current item is a person
	val isPersonItem = item.baseRowType == BaseRowType.Person ||
		item.baseItem?.type == BaseItemKind.PERSON

	val isCircular = isPersonItem && displayCirclePersonCards

	val imageUrl = remember(image, api, size, localDensity) {
		image?.getUrl(
			api,
			maxWidth = with(localDensity) { size.width.roundToPx() },
			maxHeight = with(localDensity) { size.height.roundToPx() },
		)
	}

	val cardShape = if (isCircular) CircleShape else JellyfinTheme.shapes.medium

	val animatedScale by animateFloatAsState(
		targetValue = if (focused) 1.15f else 1.0f,
		animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
		label = "BaseRowItemCardScale"
	)
	val shadowElevation by animateDpAsState(
		targetValue = if (focused) 8.dp else 0.dp,
		animationSpec = tween(durationMillis = 150),
		label = "BaseRowItemCardShadow"
	)

	val card = @Composable {
		ItemCard(
			image = {
				if (imageUrl != null) {
					AsyncImage(
						url = imageUrl,
						blurHash = image?.blurHash,
						aspectRatio = aspectRatio,
						scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP,
						modifier = Modifier
							.fillMaxSize()
					)
				} else if (item is UsbBaseRowItem || item.baseItem?.id == USB_TILE_UUID) {
					Image(
						painter = painterResource(R.drawable.ic_folder),
						contentDescription = null,
						colorFilter = ColorFilter.tint(Color(0xFFFFC107)),
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center)
					)
				} else if (item is GridButtonBaseRowItem && item.gridButton.imageRes != null) {
					Image(
						painter = painterResource(item.gridButton.imageRes),
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.fillMaxSize()
					)
				} else {
					Image(
						painter = painterResource(displayConfig.iconRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center)
					)
				}
			},
			overlay = {
				val showInfo = !usePreview && item.showCardInfoOverlay
				item.baseItem?.let { baseItem ->
					ItemCardBaseItemOverlay(
						item = baseItem,
						footer = {
							if (showInfo && title != null) {
								val focusModifier = if (focused) Modifier.basicMarquee(
									iterations = Int.MAX_VALUE,
									initialDelayMillis = 1200,
								) else Modifier

								Box(
									modifier = Modifier
										.fillMaxWidth()
										.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.6f), JellyfinTheme.shapes.small),
								) {
									Text(
										text = title,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										softWrap = false,
										textAlign = TextAlign.Center,
										color = Tokens.Color.colorWhite,
										modifier = Modifier
											.fillMaxWidth()
											.then(focusModifier)
											.padding(Tokens.Space.spaceXs),
									)
								}
							}
						}
					)
				}
			},
			modifier = Modifier
				.graphicsLayer(
					shadowElevation = with(localDensity) { shadowElevation.toPx() },
					shape = cardShape,
					clip = false
				)
				.then(cardSizeModifier)
				.then(if (isCircular) Modifier.clip(CircleShape) else Modifier)
		)
	}

	if (usePreview) {
		val focusModifier = if (focused) Modifier.basicMarquee(
			iterations = Int.MAX_VALUE,
			initialDelayMillis = 1200,
		) else Modifier

		ItemPreview(
			card = { card() },
			modifier = Modifier
				.zIndex(if (focused) 1f else 0f)
				.graphicsLayer(
					scaleX = animatedScale,
					scaleY = animatedScale
				),
			title = title?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						softWrap = false,
						textAlign = TextAlign.Center,
						modifier = Modifier
							.fillMaxWidth()
							.then(focusModifier),
					)
				}
			},
			subtitle = subtitle?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						softWrap = false,
						textAlign = TextAlign.Center,
						color = Color(0xFFAAAAAA),
						modifier = Modifier
							.fillMaxWidth()
							.then(focusModifier),
					)
				}
			},
		)
	} else {
		Box(
			modifier = Modifier
				.zIndex(if (focused) 1f else 0f)
				.graphicsLayer(
					scaleX = animatedScale,
					scaleY = animatedScale
				)
		) {
			card()
		}
	}
}

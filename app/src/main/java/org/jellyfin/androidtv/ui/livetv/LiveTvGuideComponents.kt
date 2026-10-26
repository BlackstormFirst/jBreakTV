package org.jellyfin.androidtv.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.androidtv.util.sdk.isNew

val GuideRowHeight = 55.dp
val GuideWidthPerMinute = 7.dp

@Composable
fun ProgramCell(
    program: BaseItemDto,
    durationMinutes: Int,
    isStartCut: Boolean,
    modifier: Modifier = Modifier,
    colorCodeGuide: Boolean = true,
    showNewIndicator: Boolean = false,
    showPremiereIndicator: Boolean = false,
    showRepeatIndicator: Boolean = false,
    showHDIndicator: Boolean = false,
    onClick: () -> Unit,
    onFocus: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    if (isFocused) {
        onFocus()
    }

    val width = GuideWidthPerMinute * durationMinutes

    val bgColor = if (isFocused) {
        colorResource(R.color.jellyfin_blue) // Using our primary accent color
    } else if (colorCodeGuide) {
        when {
            program.isMovie == true -> colorResource(R.color.guide_movie_bg)
            program.isNews == true -> colorResource(R.color.guide_news_bg)
            program.isSports == true -> colorResource(R.color.guide_sports_bg)
            program.isKids == true -> colorResource(R.color.guide_kids_bg)
            else -> Color.Transparent
        }
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .width(width)
            .height(GuideRowHeight)
            .background(bgColor)
            .border(1.dp, colorResource(R.color.grey_light))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .padding(2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val titleText = if (isStartCut) "<< ${program.name}" else program.name ?: stringResource(R.string.no_program_data)
            Text(
                text = titleText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                color = if (isFocused) Color.White else colorResource(R.color.white)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isStartCut && program.startDate != null) {
                    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
                    Text(
                        text = program.startDate!!.format(timeFormat),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Light,
                        color = if (isFocused) Color.White else colorResource(R.color.white)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }

                if (showNewIndicator && program.isNew() && (!showPremiereIndicator || program.isPremiere != true)) {
                    IndicatorBadge(text = stringResource(R.string.lbl_new), bgColor = Color(0xFF044B13))
                }

                if (showPremiereIndicator && program.isPremiere == true) {
                    IndicatorBadge(text = stringResource(R.string.lbl_premiere), bgColor = Color(0xFF044B13))
                }

                if (showRepeatIndicator && program.isRepeat == true) {
                    IndicatorBadge(text = stringResource(R.string.lbl_repeat), bgColor = colorResource(R.color.jellyfin_blue))
                }

                if (program.officialRating != null && program.officialRating != "0") {
                    IndicatorBadge(text = program.officialRating!!, bgColor = Color.DarkGray)
                }

                if (showHDIndicator && program.isHd == true) {
                    IndicatorBadge(text = "HD", bgColor = Color.DarkGray)
                }

                program.seriesTimerId?.let {
                    val iconRes = if (program.timerId != null) R.drawable.ic_record_series_red else R.drawable.ic_record_series
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = Color.Unspecified
                    )
                } ?: program.timerId?.let {
                    Icon(
                        painter = painterResource(R.drawable.ic_record_red),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = Color.Unspecified
                    )
                }
            }
        }
    }
}

@Composable
fun IndicatorBadge(text: String, bgColor: Color) {
    Box(
        modifier = Modifier
            .padding(end = 5.dp)
            .background(bgColor, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = Color.White
        )
    }
}

@Composable
fun ChannelHeader(
    channel: BaseItemDto,
    isFavorite: Boolean,
    imageUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(160.dp)
            .height(GuideRowHeight)
            .background(colorResource(R.color.channel_scroller_bg))
            .padding(end = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(10.dp)
                    .fillMaxHeight()
                    .background(if (isFavorite) colorResource(R.color.red) else Color.Transparent)
            )
            Spacer(modifier = Modifier.width(5.dp))

            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxSize().padding(5.dp)
                )
            } else {
                Text(
                    text = channel.name ?: "",
                    fontSize = 18.sp,
                    color = colorResource(R.color.white),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }
    }
}

@Composable
fun TimelineRow(
    startTime: LocalDateTime,
    hours: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(30.dp)
            .background(colorResource(R.color.timeline_bg)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
        for (i in 0 until (hours * 2)) { // 30 min slots
            val slotTime = startTime.plusMinutes((i * 30).toLong())
            Text(
                text = slotTime.format(timeFormat),
                color = colorResource(R.color.white),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .width(GuideWidthPerMinute * 30)
                    .padding(start = 5.dp)
            )
        }
    }
}

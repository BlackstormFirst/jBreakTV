package org.jellyfin.androidtv.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.format.DateTimeFormatter

@Composable
fun ProgramDetailDialog(
    program: BaseItemDto?,
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onPlayClicked: (BaseItemDto) -> Unit,
    onRecordClicked: (BaseItemDto) -> Unit,
    onRecordSeriesClicked: (BaseItemDto) -> Unit,
    onCancelRecordClicked: (BaseItemDto) -> Unit
) {
    if (program == null || !visible) return

    SettingsDialog(
        visible = visible,
        onDismissRequest = onDismissRequest
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(colorResource(R.color.popup_menu_background), RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                text = program.name ?: stringResource(R.string.no_program_data),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            val start = program.startDate
            val end = program.endDate
            if (start != null && end != null) {
                val formatter = DateTimeFormatter.ofPattern("HH:mm")
                val timeText = "${start.format(formatter)} - ${end.format(formatter)}"
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = timeText,
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }

            program.overview?.let { overview ->
                if (overview.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = overview,
                        fontSize = 14.sp,
                        color = Color.LightGray,
                        maxLines = 4
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onPlayClicked(program)
                        onDismissRequest()
                    }
                ) {
                    Text(text = stringResource(R.string.lbl_play))
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (program.timerId == null) {
                    Button(
                        onClick = {
                            onRecordClicked(program)
                            onDismissRequest()
                        }
                    ) {
                        Text(text = stringResource(R.string.lbl_record))
                    }

                    if (program.isSeries == true && program.seriesTimerId == null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onRecordSeriesClicked(program)
                                onDismissRequest()
                            }
                        ) {
                            Text(text = stringResource(R.string.lbl_record_series))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            onCancelRecordClicked(program)
                            onDismissRequest()
                        }
                    ) {
                        Text(text = stringResource(R.string.lbl_cancel_recording))
                    }
                }
            }
        }
    }
}

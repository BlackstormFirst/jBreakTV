package org.jellyfin.androidtv.ui.startup.fragment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.auth.model.ConnectingState
import org.jellyfin.androidtv.auth.model.ServerAdditionState
import org.jellyfin.androidtv.auth.model.UnableToConnectState
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.shared.toolbar.StartupToolbar
import org.jellyfin.androidtv.util.getSummary

@Composable
fun ServerAddScreen(
    state: ServerAdditionState?,
    initialAddress: String?,
    onSubmit: (String) -> Unit,
    openHelp: () -> Unit = {},
) {
    var address by remember { mutableStateOf(initialAddress ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val isLoading = state is ConnectingState

    LaunchedEffect(Unit) {
        if (initialAddress.isNullOrBlank()) {
            focusRequester.requestFocus()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        StartupToolbar(openHelp = openHelp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.lbl_enter_server_address),
                style = LocalTextStyle.current.copy(fontSize = 24.sp),
                color = JellyfinTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Surface wrapper to handle D-pad center correctly for IME
            Surface(
                onClick = { focusRequester.requestFocus() },
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(border = BorderStroke(2.dp, Color.White))),
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .focusRequester(focusRequester)
            ) {
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    enabled = !isLoading,
                    textStyle = LocalTextStyle.current.copy(color = JellyfinTheme.colorScheme.onInput),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (address.isNotBlank()) onSubmit(address)
                        else errorMessage = "Address cannot be empty"
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                if (address.isNotBlank()) onSubmit(address)
                                else errorMessage = "Address cannot be empty"
                                true
                            } else if (event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.Escape)) {
                                focusManager.clearFocus()
                                true
                            } else {
                                false
                            }
                        }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (address.isNotBlank()) onSubmit(address)
                    else errorMessage = "Address cannot be empty"
                },
                enabled = !isLoading
            ) {
                Text(stringResource(R.string.action_connect))
            }

            Spacer(modifier = Modifier.height(32.dp))

            val context = LocalContext.current
            val statusText = when (state) {
                is ConnectingState -> stringResource(R.string.server_connecting, state.address)
                is UnableToConnectState -> stringResource(
                    R.string.server_connection_failed_candidates,
                    state.addressCandidates
                        .map { "${it.key} - ${it.value.getSummary(context)}" }
                        .joinToString(prefix = "\n", separator = "\n")
                )
                is ConnectedState -> "Connected!"
                null -> errorMessage ?: ""
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = if (state is UnableToConnectState || errorMessage != null) Color.Red else JellyfinTheme.colorScheme.onBackground
                )
            }
        }
    }
}

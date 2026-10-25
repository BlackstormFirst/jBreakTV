package org.jellyfin.androidtv.ui.startup.fragment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.ConnectedQuickConnectState
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PendingQuickConnectState
import org.jellyfin.androidtv.auth.model.QuickConnectState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.UnavailableQuickConnectState
import org.jellyfin.androidtv.auth.model.UnknownQuickConnectState
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.ui.base.CircularProgressIndicator
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button

enum class LoginMethod {
    QuickConnect, Credentials
}

@Composable
fun UserLoginScreen(
    server: Server?,
    forcedUsername: String?,
    skipQuickConnect: Boolean,
    quickConnectState: QuickConnectState?,
    loginState: LoginState?,
    onLoginWithCredentials: (String, String) -> Unit,
    onCancel: () -> Unit
) {
    var method by remember { mutableStateOf(if (skipQuickConnect) LoginMethod.Credentials else LoginMethod.QuickConnect) }

    // If QuickConnect becomes unavailable, force credentials
    LaunchedEffect(quickConnectState) {
        if (quickConnectState == UnavailableQuickConnectState) {
            method = LoginMethod.Credentials
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 48.dp, vertical = 27.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(R.string.lbl_sign_in),
                        style = JellyfinTheme.typography.listHeader,
                        color = JellyfinTheme.colorScheme.onBackground
                    )

                    Text(
                        text = stringResource(R.string.login_connect_to, server?.name ?: "Jellyfin"),
                        color = JellyfinTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // Main Content
                    Box(modifier = Modifier.weight(1f)) {
                        when (method) {
                            LoginMethod.QuickConnect -> QuickConnectContent(quickConnectState, loginState)
                            LoginMethod.Credentials -> CredentialsContent(forcedUsername, loginState, onLoginWithCredentials)
                        }
                    }

                    // Actions
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.login_other_options),
                            color = JellyfinTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        if (method != LoginMethod.Credentials) {
                            Button(onClick = { method = LoginMethod.Credentials }) {
                                Text(stringResource(R.string.action_use_password))
                            }
                        }

                        if (method != LoginMethod.QuickConnect && quickConnectState != UnavailableQuickConnectState) {
                            Button(
                                onClick = { method = LoginMethod.QuickConnect },
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(stringResource(R.string.action_use_quickconnect))
                            }
                        }

                        Button(
                            onClick = onCancel,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(stringResource(R.string.btn_cancel))
                        }
                    }
                }
            }

            // Right Area (Background tint mask)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color(0x99000000))
            )
        }
    }
}

@Composable
private fun QuickConnectContent(quickConnectState: QuickConnectState?, loginState: LoginState?) {
    Column {
        Text(stringResource(R.string.login_quickconnect_step_1), color = JellyfinTheme.colorScheme.onBackground)
        Text(stringResource(R.string.login_quickconnect_step_2), modifier = Modifier.padding(top = 16.dp), color = JellyfinTheme.colorScheme.onBackground)
        Text(stringResource(R.string.login_quickconnect_step_3), modifier = Modifier.padding(top = 16.dp), color = JellyfinTheme.colorScheme.onBackground)

        Spacer(modifier = Modifier.height(16.dp))

        when (quickConnectState) {
            is PendingQuickConnectState -> {
                val formattedCode = buildString {
                    quickConnectState.code.forEachIndexed { index, char ->
                        if (index != 0 && index % 3 == 0) append(" ")
                        append(char)
                    }
                }
                Text(
                    text = formattedCode,
                    style = LocalTextStyle.current.copy(fontSize = 56.sp),
                    color = JellyfinTheme.colorScheme.onBackground
                )
            }
            UnavailableQuickConnectState, UnknownQuickConnectState, ConnectedQuickConnectState, null -> {
                CircularProgressIndicator()
            }
        }

        LoginErrorText(loginState, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun CredentialsContent(
    forcedUsername: String?,
    loginState: LoginState?,
    onLogin: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(forcedUsername ?: "") }
    var password by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val usernameInteractionSource = remember { MutableInteractionSource() }
    val isUsernameFocused by usernameInteractionSource.collectIsFocusedAsState()

    val passwordInteractionSource = remember { MutableInteractionSource() }
    val isPasswordFocused by passwordInteractionSource.collectIsFocusedAsState()

    val isUsernameFixed = forcedUsername != null
    val isAuthenticating = loginState == AuthenticatingState

    LaunchedEffect(Unit) {
        if (!isUsernameFixed) {
            focusRequester.requestFocus()
        } else {
            passwordFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(isUsernameFocused) {
        if (isUsernameFocused) keyboardController?.show()
    }

    LaunchedEffect(isPasswordFocused) {
        if (isPasswordFocused) keyboardController?.show()
    }

    Column {
        Text(
            text = stringResource(R.string.input_username),
            color = JellyfinTheme.colorScheme.onBackground
        )

        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(top = 8.dp)
                .background(
                    color = JellyfinTheme.colorScheme.input,
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    border = BorderStroke(
                        width = 2.dp,
                        color = if (isUsernameFocused) Color.White else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            BasicTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                enabled = !isUsernameFixed && !isAuthenticating,
                interactionSource = usernameInteractionSource,
                textStyle = LocalTextStyle.current.copy(
                    color = if (isUsernameFocused) JellyfinTheme.colorScheme.onInputFocused else JellyfinTheme.colorScheme.onInput
                ),
                cursorBrush = SolidColor(if (isUsernameFocused) Color.White else JellyfinTheme.colorScheme.onInput),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    showKeyboardOnFocus = true
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .run { if (!isUsernameFixed) focusRequester(focusRequester) else this }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter)) {
                            keyboardController?.show()
                            true
                        } else if (event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.Escape)) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            true
                        } else false
                    }
            )
        }

        Text(
            text = stringResource(R.string.input_password),
            color = JellyfinTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(top = 8.dp)
                .background(
                    color = JellyfinTheme.colorScheme.input,
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    border = BorderStroke(
                        width = 2.dp,
                        color = if (isPasswordFocused) Color.White else Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
        ) {
            BasicTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                enabled = !isAuthenticating,
                interactionSource = passwordInteractionSource,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = LocalTextStyle.current.copy(
                    color = if (isPasswordFocused) JellyfinTheme.colorScheme.onInputFocused else JellyfinTheme.colorScheme.onInput
                ),
                cursorBrush = SolidColor(if (isPasswordFocused) Color.White else JellyfinTheme.colorScheme.onInput),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    showKeyboardOnFocus = true
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboardController?.hide()
                    if (username.isNotBlank()) onLogin(username, password)
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .focusRequester(passwordFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter)) {
                            keyboardController?.show()
                            true
                        } else if (event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.Escape)) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            true
                        } else false
                    }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                keyboardController?.hide()
                if (username.isNotBlank()) onLogin(username, password)
            },
            enabled = !isAuthenticating
        ) {
            Text(stringResource(R.string.lbl_sign_in))
        }

        LoginErrorText(loginState, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun LoginErrorText(state: LoginState?, modifier: Modifier = Modifier) {
    val errorText = when (state) {
        is ServerVersionNotSupported -> stringResource(R.string.server_issue_outdated_version, state.server.version ?: "", ServerRepository.recommendedServerVersion.toString())
        AuthenticatingState -> stringResource(R.string.login_authenticating)
        RequireSignInState -> stringResource(R.string.login_invalid_credentials)
        ServerUnavailableState, is ApiClientErrorLoginState -> stringResource(R.string.login_server_unavailable)
        else -> ""
    }

    if (errorText.isNotEmpty()) {
        Text(
            text = errorText,
            color = if (state == AuthenticatingState) JellyfinTheme.colorScheme.onBackground else Color.Red,
            modifier = modifier
        )
    }
}

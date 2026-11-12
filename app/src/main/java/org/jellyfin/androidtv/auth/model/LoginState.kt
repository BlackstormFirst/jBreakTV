package org.jellyfin.androidtv.auth.model

import org.jellyfin.sdk.api.client.exception.ApiClientException

sealed class LoginState
data object AuthenticatingState : LoginState()
data object RequireSignInState : LoginState()
data class ServerUnavailableState(val cause: Throwable? = null) : LoginState()
data class ServerVersionNotSupported(val server: Server) : LoginState()
data class ApiClientErrorLoginState(val error: ApiClientException) : LoginState()
data object AuthenticatedState : LoginState()

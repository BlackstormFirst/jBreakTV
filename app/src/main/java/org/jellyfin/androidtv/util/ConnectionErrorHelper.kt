package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.sdk.discovery.RecommendedServerIssue

object ConnectionErrorUtils {

	fun cleanExceptionMessage(rawMsg: String): String {
		var msg = rawMsg.trim()
		msg = msg.replace(Regex("^(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+[a-zA-Z_][a-zA-Z0-9_]*(?:Exception|Error):\\s*"), "")
		msg = msg.replace(Regex("^(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)+[a-zA-Z_][a-zA-Z0-9_]*:\\s*"), "")
		msg = msg.replace(
			Regex("^(?:SSLHandshakeException|SSLException|CertPathValidatorException|CertificateException|CertificateExpiredException|CertificateNotYetValidException|SecureConnectionException|ApiClientException|IOException|SocketTimeoutException|UnknownHostException|ConnectException):\\s*"),
			""
		)
		return msg.trim()
	}

	private val genericWrapperMessages = setOf(
		"failed to execute request",
		"unable to sign in",
		"http request failed",
		"an error occurred while attempting to establish a secure connection",
		"base exception for the apiclient",
		"unable to connect",
		"connection failed",
	)

	private fun isGenericMessage(msg: String): Boolean {
		val lower = msg.lowercase().trim()
		return genericWrapperMessages.any { (lower == it) || (lower == "$it.") }
	}

	fun extractErrorsFromThrowable(throwable: Throwable?, context: Context? = null): List<String> {
		if (throwable == null) return emptyList()
		val rawErrors = mutableListOf<String>()
		val visited = mutableSetOf<Throwable>()

		fun collect(t: Throwable?) {
			if (t == null || !visited.add(t)) return

			val msg = t.message?.trim()
			val className = t.javaClass.name
			val simpleName = t.javaClass.simpleName

			when {
				t is java.net.SocketTimeoutException || t is org.jellyfin.sdk.api.client.exception.TimeoutException || (msg != null && msg.contains("timed out", ignoreCase = true)) -> {
					rawErrors.add(context?.getString(R.string.server_issue_timeout) ?: "Timeout")
				}
				t is java.net.UnknownHostException -> {
					val hostMsg = if (!msg.isNullOrBlank() && msg != className) " ($msg)" else ""
					rawErrors.add((context?.getString(R.string.server_issue_unable_to_connect) ?: "Hôte introuvable") + hostMsg)
				}
				t is java.net.ConnectException -> {
					rawErrors.add(context?.getString(R.string.server_issue_unable_to_connect) ?: "Connexion refusée")
				}
				msg != null && (msg.contains("401") || msg.contains("Unauthorized", ignoreCase = true) || msg.contains("Invalid username or password", ignoreCase = true)) -> {
					rawErrors.add(context?.getString(R.string.login_invalid_credentials) ?: "Mauvais mot de passe ou nom d'utilisateur")
				}
				!msg.isNullOrEmpty() && msg != className && msg != simpleName && !msg.startsWith("org.jellyfin.sdk.api.client.exception.") -> {
					val cleaned = cleanExceptionMessage(msg)
					if (cleaned.isNotEmpty()) {
						rawErrors.add(cleaned)
					}
				}
			}

			collect(t.cause)

			for (s in t.suppressed) {
				collect(s)
			}
		}

		collect(throwable)

		val unique = rawErrors.distinct()
		return unique.filterNot { isGenericMessage(it) }.ifEmpty { unique }
	}

	fun extractErrorsFromLoginState(state: LoginState?, context: Context? = null): List<String> {
		return when (state) {
			is ApiClientErrorLoginState -> extractErrorsFromThrowable(state.error, context)
			is ServerUnavailableState -> extractErrorsFromThrowable(state.cause, context).ifEmpty {
				listOf(context?.getString(R.string.server_issue_timeout) ?: "Timeout")
			}
			is RequireSignInState -> listOf(context?.getString(R.string.login_invalid_credentials) ?: "Mauvais mot de passe ou nom d'utilisateur")
			else -> emptyList()
		}
	}

	fun extractErrorsFromIssue(issue: RecommendedServerIssue, context: Context? = null): List<String> {
		return when (issue) {
			is RecommendedServerIssue.SecureConnectionFailed -> extractErrorsFromThrowable(issue.sslException, context).ifEmpty {
				listOf(context?.getString(R.string.server_issue_ssl_handshake) ?: "La négociation SSL a échoué")
			}
			is RecommendedServerIssue.ServerUnreachable -> extractErrorsFromThrowable(issue.throwable, context).ifEmpty {
				listOf(context?.getString(R.string.server_issue_timeout) ?: "Timeout")
			}
			is RecommendedServerIssue.SlowResponse -> listOf(context?.getString(R.string.server_issue_timeout) ?: "Timeout")
			is RecommendedServerIssue.MissingSystemInfo -> extractErrorsFromThrowable(issue.throwable, context).ifEmpty {
				listOf(context?.getString(R.string.server_issue_unable_to_connect) ?: "Impossible de se connecter")
			}
			is RecommendedServerIssue.InvalidProductName -> listOf(context?.getString(R.string.server_issue_invalid_product) ?: "Serveur incompatible")
			RecommendedServerIssue.MissingVersion -> listOf(context?.getString(R.string.server_issue_missing_version) ?: "Impossible de déterminer la version")
			is RecommendedServerIssue.UnsupportedServerVersion -> listOf(issue.version.toString())
			is RecommendedServerIssue.OutdatedServerVersion -> listOf(issue.version.toString())
		}
	}

	fun formatErrorDetails(errors: List<String>, context: Context? = null): String {
		val uniqueErrors = errors.distinct()
		if (uniqueErrors.isEmpty()) return ""
		val header = context?.getString(R.string.connection_errors_header) ?: "Erreur(s) :"
		return buildString {
			append(header).append("\n")
			uniqueErrors.forEach { err ->
				append("- ").append(err).append("\n")
			}
		}.trimEnd()
	}
}

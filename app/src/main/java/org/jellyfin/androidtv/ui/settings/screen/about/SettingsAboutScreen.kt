package org.jellyfin.androidtv.ui.settings.screen.about

import android.content.ClipData
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.androidtv.ui.settings.util.copyAction
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.koin.compose.koinInject
import org.jellyfin.androidtv.util.updater.CustomUpdateChecker
import org.jellyfin.androidtv.util.updater.UpdateResult
import org.jellyfin.androidtv.util.updater.ApkInstaller

@Composable
fun SettingsAboutScreen(launchedFromLogin: Boolean = false) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val userPreferences = koinInject<UserPreferences>()
	val router = LocalRouter.current

	// check if auto-update is enabled
	var autoUpdateEnabled by rememberPreference(userPreferences, UserPreferences.autoUpdateEnabled)

	var isChecking by remember { mutableStateOf(false) }
	var updateStatusText by remember { mutableStateOf("") }
	var downloadProgress by remember { mutableStateOf<Int?>(null) }

	val updateUrl = "https://home2.vlzone.com/jbreaktv/app-update.json"

	SettingsColumn {
		if (launchedFromLogin) item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_login).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		} else item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.settings).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_about_title)) },
			)
		}

		item {
			val heading = "jBreakTV app version"
			val caption = "jBreakTV ${BuildConfig.VERSION_NAME} ${BuildConfig.BUILD_TYPE}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
				modifier = Modifier.focusKey("version")
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
				headingContent = { Text("Mises à jour automatiques") },
				trailingContent = { Checkbox(checked = autoUpdateEnabled) },
				captionContent = {
					Text(if (autoUpdateEnabled) "Recherche au démarrage activée." else "Recherche au démarrage désactivée.")
				},
				onClick = {
					scope.launch {
						autoUpdateEnabled = !autoUpdateEnabled
					}
				}
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_update), contentDescription = null) },
				headingContent = { Text("Vérifier les mises à jour") },
				captionContent = {
					when {
						downloadProgress != null -> Text("Téléchargement: $downloadProgress%")
						isChecking -> Text("Vérification en cours...")
						updateStatusText.isNotEmpty() -> Text(updateStatusText)
						else -> Text("Rechercher une nouvelle version sur le serveur.")
					}
				},
				onClick = {
					if (!isChecking && downloadProgress == null) {
						isChecking = true
						updateStatusText = ""

						scope.launch {
							val checker = CustomUpdateChecker(context)
							when (val result = checker.checkForUpdate(updateUrl)) {
								is UpdateResult.Available -> {
									updateStatusText = "Nouvelle version ${result.newVersion} trouvée. Téléchargement..."
									isChecking = false

									val installer = ApkInstaller(context)
									installer.downloadAndInstall(
										downloadUrl = result.downloadUrl,
										onProgress = { progress ->
											downloadProgress = progress
										},
										onComplete = {
											downloadProgress = null
											isChecking = false
											updateStatusText = "Installation terminée."
										},
										onError = {
											downloadProgress = null
											isChecking = false
											updateStatusText = "Téléchargement échoué."
										},
										onCancel = {
											downloadProgress = null
											isChecking = false
											updateStatusText = "Téléchargement annulé."
										}
									)
								}


								is UpdateResult.UpToDate -> {
									isChecking = false
									updateStatusText = "L'application est déjà à jour."
								}

								is UpdateResult.Error -> {
									isChecking = false
									updateStatusText = "Erreur : ${result.message}"
									//updateStatusText = "Erreur de connexion."
								}

								UpdateResult.NoApkFound -> {
									isChecking = false
									updateStatusText = "Aucun fichier d'installation trouvé."
								}
							}
						}
					}
				}
			)
		}

		item {
			val heading = stringResource(R.string.pref_device_model)
			val caption = "${Build.MANUFACTURER} ${Build.MODEL}"
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_tv), contentDescription = null) },
				headingContent = { Text(heading) },
				captionContent = { Text(caption) },
				onClick = copyAction(ClipData.newPlainText(heading, caption)),
				modifier = Modifier.focusKey("device_model")
			)
		}

		item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_guide), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.licenses_link)) },
				onClick = { router.push(Routes.LICENSES) },
				modifier = Modifier.focusKey(Routes.LICENSES)
			)
		}

		if (!launchedFromLogin) item {
			ListButton(
				leadingContent = { Icon(painterResource(R.drawable.ic_flask), contentDescription = null) },
				headingContent = { Text(stringResource(R.string.pref_developer_link)) },
				onClick = { router.push(Routes.DEVELOPER) },
				modifier = Modifier.focusKey(Routes.DEVELOPER)
			)
		}
	}
}

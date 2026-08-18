package org.jellyfin.androidtv.util.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApkInstaller(private val context: Context) {

	suspend fun downloadAndInstall(
		downloadUrl: String,
		onProgress: (Int) -> Unit = {},
		onComplete: () -> Unit = {},
		onError: () -> Unit = {},
		onCancel: () -> Unit = {}
	): Boolean = withContext(Dispatchers.IO) {
		try {
			val apkFile = File(context.cacheDir, "update_installer.apk")
			if (apkFile.exists()) apkFile.delete()

			val url = URL(downloadUrl)
			val connection = url.openConnection() as HttpURLConnection
			connection.connect()

			val totalSize = connection.contentLength
			var downloadedSize = 0

			connection.inputStream.use { input ->
				apkFile.outputStream().use { output ->
					val buffer = ByteArray(8192)
					var bytesRead: Int
					while (input.read(buffer).also { bytesRead = it } != -1) {
						output.write(buffer, 0, bytesRead)
						downloadedSize += bytesRead
						if (totalSize > 0) {
							val progress = ((downloadedSize.toDouble() / totalSize) * 100).toInt()
							withContext(Dispatchers.Main) { onProgress(progress) }
						}
					}
				}
			}

			withContext(Dispatchers.Main) {
				promptInstall(context, apkFile)
			}
			true
		} catch (e: Exception) {
			e.printStackTrace()
			false
		}
	}

	private fun promptInstall(context: Context, file: File) {
		val apkUri: Uri = FileProvider.getUriForFile(
			context,
			"${context.packageName}.provider",
			file
		)

		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(apkUri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}
		context.startActivity(intent)
	}
}

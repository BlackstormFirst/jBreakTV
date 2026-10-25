package org.jellyfin.androidtv.ui.itemhandling

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent
import java.util.UUID

object ItemLauncherHelper {
	@JvmStatic
	fun getItem(
		itemId: UUID,
		onError: ((Exception) -> Unit)? = null,
		onSuccess: (BaseItemDto) -> Unit,
	) {
		ProcessLifecycleOwner.get().lifecycleScope.launch {
			val api by KoinJavaComponent.inject<ApiClient>(ApiClient::class.java)

			try {
				val response = withContext(Dispatchers.IO) {
					api.userLibraryApi.getItem(itemId = itemId).content
				}
				onSuccess(response)
			} catch (error: ApiClientException) {
				onError?.invoke(error)
			}
		}
	}
}

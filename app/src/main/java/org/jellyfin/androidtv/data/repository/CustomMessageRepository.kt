package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.constant.CustomMessage

interface CustomMessageRepository {
	val message: StateFlow<CustomMessage?>
	fun pushMessage(message: CustomMessage)
}

class CustomMessageRepositoryImpl : CustomMessageRepository {
	private val _message = MutableStateFlow<CustomMessage?>(null)
	override val message get() = _message.asStateFlow()

	override fun pushMessage(message: CustomMessage) {
		if (_message.value == message) _message.value = null
		_message.value = message
		CoroutineScope(Dispatchers.Main.immediate).launch {
			if (_message.value == message) {
				_message.value = null
			}
		}
	}
}

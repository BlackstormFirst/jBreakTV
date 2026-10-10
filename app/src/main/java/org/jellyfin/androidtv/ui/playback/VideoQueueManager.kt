package org.jellyfin.androidtv.ui.playback

import org.jellyfin.sdk.model.api.BaseItemDto

class VideoQueueManager {
	private var _currentVideoQueue: List<BaseItemDto> = emptyList()
	private var _currentMediaPosition = -1
	private var _fullSeriesQueue: List<BaseItemDto>? = null
	private var _currentSeriesEpisodeId: String? = null
	private var _selectedAudioStreamIndex: Int? = null
	private var _selectedSubtitleStreamIndex: Int? = null
	private var _lastPlayedAudioDefaultState: Boolean = false
	private var _lastPlayedAudioCodec: String? = null
	private var _lastPlayedAudioHearingImpairedState: Boolean = false
	private var _lastPlayedAudioLanguageIsoCode: String? = null
	private var _lastPlayedAudioTitle: String? = null
	private var _lastPlayedAudioIndexInType: Int? = null
	private var _lastPlayedSubtitleCodec: String? = null
	private var _lastPlayedSubtitleDefaultState: Boolean = false
	private var _lastPlayedSubtitleForcedState: Boolean = false
	private var _lastPlayedSubtitleHearingImpairedState: Boolean = false
	private var _lastPlayedSubtitleLanguageIsoCode: String? = null
	private var _lastPlayedSubtitleTitle: String? = null
	private var _lastPlayedVideoDefaultState: Boolean? = null
	private var _lastPlayedVideoIndexInType: Int? = null
	private var _lastPlayedVideoCodec: String? = null
	private var _lastPlayedVideoTitle: String? = null

	fun setFullSeriesQueue(allEpisodes: List<BaseItemDto>?) {
		_fullSeriesQueue = allEpisodes?.toList()
		_currentSeriesEpisodeId = null
	}

	fun getFullSeriesQueue(): List<BaseItemDto>? = _fullSeriesQueue

	fun updateWindowForEpisode(currentEpisode: BaseItemDto) {
		val fullList = _fullSeriesQueue ?: return
		val fullIndex = fullList.indexOfFirst { it.id == currentEpisode.id }
		if (fullIndex == -1) return

		_currentVideoQueue = fullList.toMutableList()
		_currentMediaPosition = fullIndex
		_currentSeriesEpisodeId = currentEpisode.id.toString()
	}

	fun setCurrentVideoQueue(items: List<BaseItemDto>?) {
		if (items.isNullOrEmpty()) {
			_currentVideoQueue = emptyList()
			_currentMediaPosition = -1
			_fullSeriesQueue = null
			_currentSeriesEpisodeId = null
			return
		}

		_currentVideoQueue = items.toMutableList()
		if (_currentMediaPosition !in _currentVideoQueue.indices) {
			_currentMediaPosition = 0
		}
	}

	fun getCurrentVideoQueue(): List<BaseItemDto> = _currentVideoQueue

	fun setCurrentMediaPosition(currentMediaPosition: Int) {
		if (currentMediaPosition !in 0.._currentVideoQueue.size) return

		_currentMediaPosition = currentMediaPosition
		val currentItem = _currentVideoQueue.getOrNull(currentMediaPosition)
		if (currentItem != null && !_fullSeriesQueue.isNullOrEmpty()) {
			updateWindowForEpisode(currentItem)
		}
	}

	fun getCurrentMediaPosition() = _currentMediaPosition

	fun getLastPlayedAudioCodec(): String? {
		return _lastPlayedAudioCodec
	}

	fun setLastPlayedAudioCodec(codec: String) {
		_lastPlayedAudioCodec = codec
	}
	fun getLastPlayedAudioDefaultState (): Boolean {
		return _lastPlayedAudioDefaultState
	}

	fun setLastPlayedAudioDefaultState(state: Boolean) {
		_lastPlayedAudioDefaultState = state
	}

	fun getLastPlayedAudioHearingImpairedState(): Boolean {
		return _lastPlayedAudioHearingImpairedState
	}

	fun setLastPlayedAudioHearingImpairedState(state: Boolean) {
		_lastPlayedAudioHearingImpairedState = state
	}

	fun getLastPlayedAudioLanguageIsoCode(): String? {
		return _lastPlayedAudioLanguageIsoCode
	}

	fun setLastPlayedAudioLanguageIsoCode(isoCode: String) {
		_lastPlayedAudioLanguageIsoCode = isoCode
	}

	fun getLastPlayedAudioTitle(): String? {
		return _lastPlayedAudioTitle
	}

	fun setLastPlayedAudioTitle(title: String?) {
		_lastPlayedAudioTitle = title
	}

	fun getLastPlayedAudioIndexInType(): Int? {
		return _lastPlayedAudioIndexInType
	}

	fun setLastPlayedAudioIndexInType(index: Int?) {
		_lastPlayedAudioIndexInType = index
	}

	fun getLastPlayedVideoDefaultState(): Boolean? {
		return _lastPlayedVideoDefaultState
	}

	fun setLastPlayedVideoDefaultState(state: Boolean?) {
		_lastPlayedVideoDefaultState = state
	}

	fun getLastPlayedVideoIndexInType(): Int? {
		return _lastPlayedVideoIndexInType
	}

	fun setLastPlayedVideoIndexInType(index: Int?) {
		_lastPlayedVideoIndexInType = index
	}

	fun getLastPlayedVideoCodec(): String? {
		return _lastPlayedVideoCodec
	}

	fun setLastPlayedVideoCodec(codec: String?) {
		_lastPlayedVideoCodec = codec
	}

	fun getLastPlayedVideoTitle(): String? {
		return _lastPlayedVideoTitle
	}

	fun setLastPlayedVideoTitle(title: String?) {
		_lastPlayedVideoTitle = title
	}

	fun getLastPlayedSubtitleCodec(): String? {
		return _lastPlayedSubtitleCodec
	}

	fun setLastPlayedSubtitleCodec(codecTag: String?) {
		_lastPlayedSubtitleCodec = codecTag
	}

	fun getLastPlayedSubtitleDefaultState(): Boolean {
		return _lastPlayedSubtitleDefaultState
	}

	fun setLastPlayedSubtitleDefaultState(state: Boolean) {
		_lastPlayedSubtitleDefaultState = state
	}

	fun getLastPlayedSubtitleForcedState(): Boolean {
		return _lastPlayedSubtitleForcedState
	}

	fun setLastPlayedSubtitleForcedState(state: Boolean) {
		_lastPlayedSubtitleForcedState = state
	}

	fun getLastPlayedSubtitleHearingImpairedState(): Boolean {
		return _lastPlayedSubtitleHearingImpairedState
	}

	fun setLastPlayedSubtitleHearingImpairedState(state: Boolean) {
		_lastPlayedSubtitleHearingImpairedState = state
	}

	fun getLastPlayedSubtitleLanguageIsoCode(): String? {
		return _lastPlayedSubtitleLanguageIsoCode
	}

	fun setLastPlayedSubtitleLanguageIsoCode(isoCode: String?) {
		_lastPlayedSubtitleLanguageIsoCode = isoCode
	}

	fun getLastPlayedSubtitleTitle(): String? {
		return _lastPlayedSubtitleTitle
	}

	fun setLastPlayedSubtitleTitle(title: String?) {
		_lastPlayedSubtitleTitle = title
	}

	fun getSelectedAudioStreamIndex(): Int? = _selectedAudioStreamIndex
	fun setSelectedAudioStreamIndex(index: Int?) { _selectedAudioStreamIndex = index }

	fun getSelectedSubtitleStreamIndex(): Int? = _selectedSubtitleStreamIndex
	fun setSelectedSubtitleStreamIndex(index: Int?) { _selectedSubtitleStreamIndex = index }

	private var _selectedVideoStreamIndex: Int? = null
	fun getSelectedVideoStreamIndex(): Int? = _selectedVideoStreamIndex
	fun setSelectedVideoStreamIndex(index: Int?) { _selectedVideoStreamIndex = index }

	fun clearVideoQueue() {
		_currentVideoQueue = emptyList()
		_currentMediaPosition = -1
		_fullSeriesQueue = null
		_currentSeriesEpisodeId = null
		_selectedAudioStreamIndex = null
		_selectedSubtitleStreamIndex = null
		_selectedVideoStreamIndex = null
	}
}

package org.jellyfin.androidtv.ui.itemhandling

import android.content.Context
import org.jellyfin.androidtv.constant.LiveTvOption
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.ui.navigation.Destination
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.androidtv.util.sdk.compat.copyWithDisplayPreferencesId
import org.jellyfin.androidtv.util.usbdevices.USB_TILE_UUID
import org.jellyfin.androidtv.util.usbdevices.UsbBaseRowItem
import org.jellyfin.androidtv.util.usbdevices.UsbHomeDecorator
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber

class ItemLauncher {
	private val navigationRepository: NavigationRepository by inject(NavigationRepository::class.java)
	private val preferencesRepository: PreferencesRepository by inject(PreferencesRepository::class.java)
	private val mediaManager: MediaManager by inject(MediaManager::class.java)
	private val playbackLauncher: PlaybackLauncher by inject(PlaybackLauncher::class.java)
	private val playbackHelper: PlaybackHelper by inject(PlaybackHelper::class.java)

	fun launchUserView(baseItem: BaseItemDto?) {
		Timber.d("**** Collection type: %s", baseItem?.collectionType)
		val destination = getUserViewDestination(baseItem)
		navigationRepository.navigate(destination)
	}

	fun getUserViewDestination(baseItem: BaseItemDto?): Destination.Fragment {
		val collectionType = baseItem?.collectionType ?: CollectionType.UNKNOWN

		return when (collectionType) {
			CollectionType.MOVIES, CollectionType.TVSHOWS -> {
				val displayPreferences = preferencesRepository.getLibraryPreferences(baseItem?.displayPreferencesId ?: "")
				val enableSmartScreen = displayPreferences[LibraryPreferences.enableSmartScreen]

				if (!enableSmartScreen && baseItem != null) Destinations.libraryBrowser(baseItem.id, null)
				else if (baseItem != null) Destinations.librarySmartScreen(baseItem.id)
				else Destinations.home
			}
			CollectionType.MUSIC, CollectionType.LIVETV -> {
				if (baseItem != null) Destinations.librarySmartScreen(baseItem.id)
				else Destinations.home
			}
			else -> {
				if (baseItem != null) Destinations.libraryBrowser(baseItem.id, null)
				else Destinations.home
			}
		}
	}

	@Suppress("LongMethod", "CyclomaticComplexMethod")
	fun launch(rowItem: BaseRowItem, adapter: Any?, context: Context) {
		if (rowItem is UsbBaseRowItem || (rowItem.baseItem != null && USB_TILE_UUID == rowItem.baseItem?.id)) {
			UsbHomeDecorator.handleUsbTileClick(context, navigationRepository)
			return
		}

		when (rowItem.baseRowType) {
			BaseRowType.BaseItem -> {
				var baseItem = rowItem.baseItem ?: return
				try {
					Timber.i("Item selected: %s (%s)", baseItem.name, baseItem.type.toString())
				} catch (e: Exception) {
					// swallow it
				}

				when (baseItem.type) {
					BaseItemKind.USER_VIEW, BaseItemKind.COLLECTION_FOLDER -> {
						launchUserView(baseItem)
						return
					}
					BaseItemKind.SERIES, BaseItemKind.MUSIC_ARTIST -> {
						navigationRepository.navigate(Destinations.itemDetails(baseItem.id))
						return
					}
					BaseItemKind.MUSIC_ALBUM, BaseItemKind.PLAYLIST -> {
						navigationRepository.navigate(Destinations.itemList(baseItem.id))
						return
					}
					BaseItemKind.AUDIO -> {
						if (mediaManager.hasAudioQueueItems() && rowItem is AudioQueueBaseRowItem && rowItem.baseItem?.id == mediaManager.currentAudioItem?.id) {
							navigationRepository.navigate(Destinations.nowPlaying)
						} else if (mediaManager.hasAudioQueueItems() && rowItem is AudioQueueBaseRowItem) {
							Timber.d("playing audio queue item")
							mediaManager.playFrom(rowItem.queueEntry)
						} else if (adapter is ItemRowAdapter && adapter.queryType == QueryType.Search) {
							playbackLauncher.launch(context, listOf(baseItem))
						} else {
							Timber.d("playing audio item")
							val audioItemsAsList = ArrayList<BaseItemDto>()
							if (adapter is Iterable<*>) {
								for (item in adapter) {
									if (item is BaseRowItem && item.baseItem != null) {
										audioItemsAsList.add(item.baseItem!!)
									}
								}
							}
							val index = if (adapter is List<*>) adapter.indexOf(rowItem) else 0
							playbackLauncher.launch(context, audioItemsAsList, 0, false, index)
						}
						return
					}
					BaseItemKind.SEASON -> {
						navigationRepository.navigate(Destinations.folderBrowser(baseItem.id))
						return
					}
					BaseItemKind.BOX_SET -> {
						navigationRepository.navigate(Destinations.collectionBrowser(baseItem.id))
						return
					}
					BaseItemKind.PHOTO -> {
						if (adapter is ItemRowAdapter) {
							navigationRepository.navigate(
								Destinations.photoPlayer(
									baseItem.id,
									false,
									adapter.sortBy,
									adapter.sortOrder
								)
							)
						}
						return
					}
					else -> {}
				}

				if (baseItem.isFolder == true) {
					if (baseItem.displayPreferencesId == null) {
						baseItem = baseItem.copyWithDisplayPreferencesId(baseItem.id.toString())
					}
					navigationRepository.navigate(Destinations.libraryBrowser(baseItem.id, null))
				} else {
					when (rowItem.selectAction) {
						BaseRowItemSelectAction.ShowDetails -> {
							navigationRepository.navigate(Destinations.itemDetails(baseItem.id))
						}
						BaseRowItemSelectAction.Play -> {
							val targetItem = baseItem
							playbackHelper.getItemsToPlay(context, targetItem, targetItem.type == BaseItemKind.MOVIE, false, object : Response<List<BaseItemDto>>() {
								override fun onResponse(response: List<BaseItemDto>) {
									if (!isActive) return
									var itemsPosition = response.indexOfFirst { it.id == targetItem.id }
									var finalItems = response
									if (itemsPosition == -1) {
										if (targetItem.isFolder != true) {
											finalItems = ArrayList(response)
											(finalItems as ArrayList<BaseItemDto>).add(0, targetItem)
										}
										itemsPosition = 0
									}
									var pos = 0
									val ticks = targetItem.userData?.playbackPositionTicks
									if (ticks != null && ticks > 0) {
										pos = (ticks / 10000).toInt()
									}
									playbackLauncher.launch(context, finalItems, pos, false, itemsPosition, false)
								}
							})
						}
					}
				}
			}
			BaseRowType.Person -> {
				val id = rowItem.itemId
				if (id != null) {
					navigationRepository.navigate(Destinations.itemDetails(id))
				}
			}
			BaseRowType.Chapter -> {
				val chapter = (rowItem as ChapterItemInfoBaseRowItem).chapterInfo
				val id = rowItem.itemId
				ItemLauncherHelper.getItem(id, object : Response<BaseItemDto>() {
					override fun onResponse(response: BaseItemDto) {
						if (!isActive) return
						val start = chapter.startPositionTicks / 10000
						playbackLauncher.launch(context, listOf(response), start.toInt())
					}
				})
			}
			BaseRowType.LiveTvProgram -> {
				val program = rowItem.baseItem ?: return
				when (rowItem.selectAction) {
					BaseRowItemSelectAction.ShowDetails -> {
						navigationRepository.navigate(Destinations.channelDetails(program.id))
					}
					BaseRowItemSelectAction.Play -> {
						val channelId = program.channelId ?: return
						ItemLauncherHelper.getItem(channelId, object : Response<BaseItemDto>() {
							override fun onResponse(response: BaseItemDto) {
								if (!isActive) return
								playbackLauncher.launch(context, listOf(response))
							}
						})
					}
				}
			}
			BaseRowType.LiveTvChannel -> {
				val channel = rowItem.baseItem ?: return
				ItemLauncherHelper.getItem(channel.id, object : Response<BaseItemDto>() {
					override fun onResponse(response: BaseItemDto) {
						if (!isActive) return
						playbackHelper.getItemsToPlay(context, response, false, false, object : Response<List<BaseItemDto>>() {
							override fun onResponse(response: List<BaseItemDto>) {
								if (!isActive) return
								playbackLauncher.launch(context, response)
							}
						})
					}
				})
			}
			BaseRowType.LiveTvRecording -> {
				val recording = rowItem.baseItem ?: return
				when (rowItem.selectAction) {
					BaseRowItemSelectAction.ShowDetails -> {
						navigationRepository.navigate(Destinations.itemDetails(recording.id))
					}
					BaseRowItemSelectAction.Play -> {
						ItemLauncherHelper.getItem(recording.id, object : Response<BaseItemDto>() {
							override fun onResponse(response: BaseItemDto) {
								if (!isActive) return
								playbackLauncher.launch(context, listOf(response))
							}
						})
					}
				}
			}
			BaseRowType.SeriesTimer -> {
				val id = rowItem.itemId
				if (id != null) {
					navigationRepository.navigate(Destinations.seriesTimerDetails(id))
				}
			}
			BaseRowType.GridButton -> {
				when ((rowItem as GridButtonBaseRowItem).gridButton.id) {
					LiveTvOption.LIVE_TV_GUIDE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvGuide)
					LiveTvOption.LIVE_TV_RECORDINGS_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvRecordings)
					LiveTvOption.LIVE_TV_SERIES_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSeriesRecordings)
					LiveTvOption.LIVE_TV_SCHEDULE_OPTION_ID -> navigationRepository.navigate(Destinations.liveTvSchedule)
				}
			}
		}
	}
}

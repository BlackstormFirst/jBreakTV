@file:JvmName("ModelUtils")

package org.jellyfin.androidtv.util.sdk

import org.jellyfin.androidtv.auth.model.PublicUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.TimerInfoDto

fun SeriesTimerInfoDto.asTimerInfoDto() = TimerInfoDto(
	id = id,
	type = type,
	serverId = serverId,
	externalId = externalId,
	channelId = channelId,
	externalChannelId = externalChannelId,
	channelName = channelName,
	channelPrimaryImageTag = channelPrimaryImageTag,
	programId = programId,
	externalProgramId = externalProgramId,
	name = name,
	overview = overview,
	startDate = startDate,
	endDate = endDate,
	serviceName = serviceName,
	priority = priority,
	prePaddingSeconds = prePaddingSeconds,
	postPaddingSeconds = postPaddingSeconds,
	isPrePaddingRequired = isPrePaddingRequired,
	parentBackdropItemId = parentBackdropItemId,
	parentBackdropImageTags = parentBackdropImageTags,
	isPostPaddingRequired = isPostPaddingRequired,
	keepUntil = keepUntil,
)

fun ServerDiscoveryInfo.toServer(): Server = Server(
	id = id.toUUID(),
	name = name,
	address = address,
)

fun UserDto.toPublicUser(): PublicUser? {
	return PublicUser(
		id = id,
		name = name ?: return null,
		serverId = serverId?.toUUIDOrNull() ?: return null,
		accessToken = null,
		imageTag = primaryImage?.tag
	)
}

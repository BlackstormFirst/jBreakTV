package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateUtils
import android.widget.LinearLayout
import android.widget.TextView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

object TvManager : KoinComponent {
    private val systemPreferences: SystemPreferences by inject()
    private val liveTvPreferences: LiveTvPreferences by inject()
    private val api: ApiClient by inject()

    private var allChannels: MutableList<BaseItemDto>? = null
    private var channelIds: Array<UUID>? = null
    private var mProgramsDict = HashMap<UUID, ArrayList<BaseItemDto>>()
    private var needLoadTime: LocalDateTime? = null
    private var forceReload = false

    @JvmStatic
    fun getLastLiveTvChannel(): UUID? {
        return systemPreferences.get(SystemPreferences.liveTvLastChannel).toUUIDOrNull()
    }

    @JvmStatic
    fun setLastLiveTvChannel(id: UUID) {
        val prev = systemPreferences.get(SystemPreferences.liveTvLastChannel)
        systemPreferences.set(SystemPreferences.liveTvPrevChannel, prev)
        systemPreferences.set(SystemPreferences.liveTvLastChannel, id.toString())
        updateLastPlayedDate(id)
        fillChannelIds()
    }

    @JvmStatic
    fun getPrevLiveTvChannel(): UUID? {
        return systemPreferences.get(SystemPreferences.liveTvPrevChannel).toUUIDOrNull()
    }

    @JvmStatic
    fun getAllChannels(): List<BaseItemDto> {
        return allChannels ?: emptyList()
    }

    @JvmStatic
    fun reset() {
        allChannels = null
        channelIds = null
        mProgramsDict = HashMap()
        needLoadTime = null
        forceReload = true
    }

    @JvmStatic
    fun forceReload() {
        forceReload = true
    }

    @JvmStatic
    fun shouldForceReload(): Boolean = forceReload

    @JvmStatic
    fun getAllChannelsIndex(id: UUID?): Int {
        if (allChannels == null || id == null) return -1
        return allChannels!!.indexOfFirst { it.id == id }
    }

    @JvmStatic
    fun getChannel(ndx: Int): BaseItemDto? {
        val channels = getAllChannels()
        return if (ndx in channels.indices) channels[ndx] else null
    }

    @JvmStatic
    fun updateLastPlayedDate(channelId: UUID) {
        allChannels?.let { channels ->
            val ndx = getAllChannelsIndex(channelId)
            if (ndx >= 0) {
                channels[ndx] = channels[ndx].copyWithLastPlayedDate(LocalDateTime.now())
            }
        }
    }

    suspend fun loadAllChannels(): Int {
        val channels = fetchLiveTvChannels(api, liveTvPreferences)
        return if (channels != null) {
            allChannels = channels.toMutableList()
            fillChannelIds()
        } else {
            0
        }
    }

    @JvmStatic
    fun loadAllChannels(fragment: Fragment, callback: (Int) -> Unit) {
        fragment.lifecycleScope.launch {
            val res = loadAllChannels()
            callback(res)
        }
    }

    @JvmStatic
    fun getProgramsAsync(fragment: Fragment, startNdx: Int, endNdx: Int, startTime: LocalDateTime, endTime: LocalDateTime, callback: Runnable?) {
        fragment.lifecycleScope.launch {
            getPrograms(startNdx, endNdx, startTime, endTime)
            callback?.run()
        }
    }

    private fun fillChannelIds(): Int {
        var ndx = 0
        allChannels?.let { channels ->
            val ids = Array(channels.size) { channels[it].id }
            channelIds = ids
            val last = getLastLiveTvChannel()
            if (last != null) {
                val index = channels.indexOfFirst { it.id == last }
                if (index >= 0) ndx = index
            }
        }
        return ndx
    }

    suspend fun getPrograms(startNdx: Int, endNdx: Int, startTime: LocalDateTime, endTime: LocalDateTime): Map<UUID, List<BaseItemDto>> {
        val startTimeRounded = startTime.withMinute(if (startTime.minute >= 30) 30 else 0).withSecond(0).withNano(0)
        val endTimeRounded = endTime.minusSeconds(1)

        val ids = channelIds ?: return emptyMap()
        val actualEndNdx = if (endNdx > ids.size) ids.size else endNdx + 1
        val slice = ids.sliceArray(startNdx until actualEndNdx).toList()

        val programs = fetchPrograms(api, slice, startTimeRounded, endTimeRounded)
        if (programs != null) {
            buildProgramsDict(programs, startTime)
        }
        return mProgramsDict
    }

    private fun buildProgramsDict(programs: Collection<BaseItemDto>, startTime: LocalDateTime) {
        mProgramsDict = HashMap()
        for (program in programs) {
            val id = program.channelId ?: continue
            val endDate = program.endDate ?: continue
            if (endDate.isAfter(startTime)) {
                mProgramsDict.getOrPut(id) { ArrayList() }.add(program)
            }
        }
        needLoadTime = startTime.plusMinutes(29)
    }

    @JvmStatic
    fun getProgramsForChannel(channelId: UUID, filters: GuideFilters? = null): List<BaseItemDto> {
        val results = mProgramsDict[channelId] ?: return emptyList()
        if (filters == null || !filters.any()) return results

        return results.filter { filters.passesFilter(it) }
    }

    @JvmStatic
    fun setTimelineRow(context: Context, timelineRow: LinearLayout, program: BaseItemDto) {
        timelineRow.removeAllViews()
        val local = program.startDate ?: return

        val on = TextView(context).apply {
            text = context.resources.getString(R.string.lbl_on)
        }
        timelineRow.addView(on)

        val channel = TextView(context).apply {
            text = program.channelName
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.resources.getColor(android.R.color.holo_blue_light))
        }
        timelineRow.addView(channel)

        val datetime = TextView(context).apply {
            text = StringBuilder()
                .append(TimeUtils.getFriendlyDate(context, local))
                .append(" @ ")
                .append(context.getTimeFormatter().format(local))
                .append(" (")
                .append(DateUtils.getRelativeTimeSpanString(local.toInstant(ZoneOffset.UTC).toEpochMilli(), Instant.now().toEpochMilli(), 0))
                .append(")")
        }
        timelineRow.addView(datetime)
    }
}

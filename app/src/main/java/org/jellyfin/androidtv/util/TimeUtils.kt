package org.jellyfin.androidtv.util

import android.annotation.SuppressLint
import android.content.Context
import org.jellyfin.androidtv.R
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

object TimeUtils {
    private const val MILLIS_PER_SEC = 1000
    private val MILLIS_PER_MIN = TimeUnit.MINUTES.toMillis(1)
    private val MILLIS_PER_HR = TimeUnit.HOURS.toMillis(1)

    private const val SECS_PER_MIN = 60
    private val SECS_PER_HR = TimeUnit.HOURS.toSeconds(1)

    private const val DURATION_TIME_FORMAT_NO_HOURS = "%d:%02d"
    private const val DURATION_TIME_FORMAT_WITH_HOURS = "%d:%02d:%02d"

    @JvmStatic
    fun secondsToMillis(seconds: Double): Long {
        return (seconds * MILLIS_PER_SEC).roundToLong()
    }

    /**
     * Formats time in milliseconds to hh:mm:ss string format.
     *
     * @param millis Time in milliseconds
     * @return Formatted time
     */
    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun formatMillis(millis: Long): String {
        var mutableMillis = millis
        val hr = TimeUnit.MILLISECONDS.toHours(mutableMillis)
        mutableMillis %= MILLIS_PER_HR
        val min = TimeUnit.MILLISECONDS.toMinutes(mutableMillis)
        mutableMillis %= MILLIS_PER_MIN
        val sec = TimeUnit.MILLISECONDS.toSeconds(mutableMillis)

        return if (hr > 0) {
            String.format(DURATION_TIME_FORMAT_WITH_HOURS, hr, min, sec)
        } else {
            String.format(DURATION_TIME_FORMAT_NO_HOURS, min, sec)
        }
    }

    @JvmStatic
    fun formatSeconds(context: Context, seconds: Int): String {
        return if (seconds < SECS_PER_MIN) {
            context.getQuantityString(R.plurals.seconds, seconds)
        } else if (seconds < SECS_PER_HR) {
            context.getQuantityString(R.plurals.minutes, seconds / SECS_PER_MIN)
        } else {
            context.getQuantityString(R.plurals.hours, seconds / SECS_PER_HR)
        }
    }

    /**
     * Formats time in seconds to hh:mm:ss string format.
     *
     * @param seconds Time in seconds
     * @return Formatted time
     */
    @SuppressLint("DefaultLocale")
    @JvmStatic
    fun formatSeconds(seconds: Long): String {
        var mutableSeconds = seconds
        val hr = TimeUnit.SECONDS.toHours(mutableSeconds)
        mutableSeconds %= SECS_PER_HR
        val min = TimeUnit.SECONDS.toMinutes(mutableSeconds)
        mutableSeconds %= SECS_PER_MIN
        val sec = TimeUnit.SECONDS.toSeconds(mutableSeconds)

        return if (hr > 0) {
            String.format(DURATION_TIME_FORMAT_WITH_HOURS, hr, min, sec)
        } else {
            String.format(DURATION_TIME_FORMAT_NO_HOURS, min, sec)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getFriendlyDate(context: Context, dateTime: LocalDateTime, relative: Boolean = false): String {
        val now = LocalDateTime.now()

        if (dateTime.year == now.year) {
            if (dateTime.dayOfYear == now.dayOfYear) {
                return context.getString(R.string.lbl_today)
            }
            if (dateTime.dayOfYear == now.dayOfYear + 1) {
                return context.getString(R.string.lbl_tomorrow)
            }
            if (dateTime.dayOfYear < now.dayOfYear + 7 && dateTime.dayOfYear > now.dayOfYear) {
                return dateTime.format(DateTimeFormatter.ofPattern("EE", context.locale))
            }
            if (relative) {
                return context.getString(R.string.lbl_in_x_days, dateTime.dayOfYear - now.dayOfYear)
            }
        }

        return context.getDateFormatter().format(dateTime)
    }
}

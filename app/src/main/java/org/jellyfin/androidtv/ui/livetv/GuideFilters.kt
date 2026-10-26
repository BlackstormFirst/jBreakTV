package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class GuideFilters : KoinComponent {
    private val systemPreferences: SystemPreferences by inject()

    var movies: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterMovies, value)
        }

    var news: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterNews, value)
        }

    var series: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterSeries, value)
        }

    var kids: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterKids, value)
        }

    var sports: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterSports, value)
        }

    var premiere: Boolean = false
        set(value) {
            field = value
            systemPreferences.set(SystemPreferences.liveTvGuideFilterPremiere, value)
        }

    init {
        load()
    }

    fun load() {
        movies = systemPreferences.get(SystemPreferences.liveTvGuideFilterMovies)
        news = systemPreferences.get(SystemPreferences.liveTvGuideFilterNews)
        series = systemPreferences.get(SystemPreferences.liveTvGuideFilterSeries)
        kids = systemPreferences.get(SystemPreferences.liveTvGuideFilterKids)
        sports = systemPreferences.get(SystemPreferences.liveTvGuideFilterSports)
        premiere = systemPreferences.get(SystemPreferences.liveTvGuideFilterPremiere)
    }

    fun any(): Boolean = movies || news || series || kids || sports || premiere

    fun passesFilter(program: BaseItemDto): Boolean {
        if (!any()) return true

        if (movies && program.isMovie == true) return !premiere || program.isPremiere == true
        if (news && program.isNews == true) return !premiere || program.isPremiere == true || program.isLive == true || program.isRepeat != true
        if (series && program.isSeries == true) return !premiere || program.isPremiere == true || program.isRepeat != true
        if (kids && program.isKids == true) return !premiere || program.isPremiere == true
        if (sports && program.isSports == true) return !premiere || program.isPremiere == true || program.isLive == true
        if (!movies && !news && !series && !kids && !sports) {
            return premiere && (program.isPremiere == true || (program.isSeries == true && program.isRepeat != true) || (program.isSports == true && program.isLive == true))
        }

        return false
    }

    fun clear() {
        news = false
        series = false
        sports = false
        kids = false
        movies = false
        premiere = false
    }

    override fun toString(): String {
        return if (any()) "Content filtered. Showing channels with ${getFilterString()}" else "Showing all programs "
    }

    private fun getFilterString(): String {
        var filterString = ""
        if (movies) filterString += "movies"
        if (news) filterString += getSeparator(filterString) + "news"
        if (sports) filterString += getSeparator(filterString) + "sports"
        if (series) filterString += getSeparator(filterString) + "series"
        if (kids) filterString += getSeparator(filterString) + "kids"
        if (premiere) filterString += getSeparator(filterString) + "ONLY new"

        return filterString
    }

    private fun getSeparator(original: String): String = if (original.isNotEmpty()) ", " else ""
}

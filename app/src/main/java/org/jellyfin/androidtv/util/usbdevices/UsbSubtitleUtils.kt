package org.jellyfin.androidtv.util.usbdevices

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.MediaStreamType
import java.io.File
import java.util.Locale

@OptIn(UnstableApi::class)
object UsbSubtitleUtils {

    data class SubtitleMeta(
        val displayTitle: String,
        val language: String?,
        val isForced: Boolean,
        val isHearingImpaired: Boolean
    )

    fun findSidecarSubtitles(mediaFile: File): List<File> {
        val parent = mediaFile.parentFile ?: return emptyList()
        val mediaNameWithoutExt = mediaFile.nameWithoutExtension.lowercase(Locale.ROOT)

        val files = parent.listFiles() ?: return emptyList()
        return files.filter { file ->
            UsbMediaHelper.isSubtitleFile(file) && file.nameWithoutExtension.lowercase(Locale.ROOT).startsWith(mediaNameWithoutExt)
        }.sortedWith(UsbMediaHelper.naturalFileComparator)
    }

    fun formatSubtitleDisplayTitle(
        title: String?,
        languageDisplayName: String,
        isForced: Boolean,
        isHearingImpaired: Boolean,
        codecUpper: String,
        context: Context? = null
    ): String {
        val forcedTag = if (isForced) " - Forcé" else ""
        val sdhStr = context?.getString(R.string.indicator_subtitles_hearing_impaired) ?: "SDH"
        val sdhTag = if (isHearingImpaired) " - $sdhStr" else ""

        return if (!title.isNullOrBlank() && title != languageDisplayName) {
            "$title - $languageDisplayName$forcedTag$sdhTag - $codecUpper"
        } else {
            "$languageDisplayName$forcedTag$sdhTag - $codecUpper"
        }
    }

    fun resolveTrackTitle(
        format: Format,
        msType: MediaStreamType,
        languageDisplayName: String,
        codecUpper: String,
        channels: Int,
        isForced: Boolean,
        isHearingImpaired: Boolean = false,
        context: Context? = null
    ): String {
        val originalTitle = format.label
        val offsetMs = if (format.subsampleOffsetUs != Format.OFFSET_SAMPLE_RELATIVE && format.subsampleOffsetUs != 0L) {
            format.subsampleOffsetUs / 1000L
        } else {
            0L
        }
        val offsetTag = if (offsetMs != 0L) " (Offset ${offsetMs}ms)" else ""

        val baseTitle = when (msType) {
            MediaStreamType.AUDIO -> {
                val channelLayout = AudioChannelHelper.formatChannelLayout(channels)
                val layoutSuffix = if (channelLayout.isNotBlank()) " $channelLayout" else ""
                if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) {
                    "$originalTitle - $languageDisplayName - $codecUpper$layoutSuffix"
                } else {
                    "$languageDisplayName - $codecUpper$layoutSuffix"
                }
            }
            MediaStreamType.SUBTITLE -> {
                val cleanTitle = if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) originalTitle else null
                formatSubtitleDisplayTitle(cleanTitle, languageDisplayName, isForced, isHearingImpaired, codecUpper, context)
            }
            else -> {
                if (!originalTitle.isNullOrBlank() && originalTitle != languageDisplayName) {
                    "$originalTitle - $languageDisplayName"
                } else {
                    languageDisplayName
                }
            }
        }

        return "$baseTitle$offsetTag"
    }

    fun getDisplayNameForLanguage(langCode: String?, context: Context? = null): String {
        val unknownStr = context?.getString(R.string.lbl_bracket_unknown) ?: "Unknown"
        if (langCode.isNullOrBlank()) return unknownStr

        val normalizedLang = when (val lower = langCode.lowercase(Locale.ROOT)) {
            "fre", "fra" -> "fr"
            "ger", "deu" -> "de"
            "eng" -> "en"
            "spa" -> "es"
            "ita" -> "it"
            "jpn" -> "ja"
            "chi", "zho" -> "zh"
            "rus" -> "ru"
            "por" -> "pt"
            "dut", "nld" -> "nl"
            "pol" -> "pl"
            "kor" -> "ko"
            "swe" -> "sv"
            "nor" -> "no"
            "fin" -> "fi"
            "dan" -> "da"
            "ara" -> "ar"
            "hin" -> "hi"
            "tur" -> "tr"
            "ukr" -> "uk"
            "cze", "ces" -> "cs"
            "gre", "ell" -> "el"
            "hun" -> "hu"
            "ron", "rum" -> "ro"
            else -> lower
        }

        val locale = Locale.forLanguageTag(normalizedLang)
        val display = locale.getDisplayLanguage(Locale.getDefault())
        return if (display.isNotBlank() && display != normalizedLang && display != langCode) {
            display.replaceFirstChar { it.uppercase() }
        } else {
            runCatching { Locale.Builder().setLanguage(normalizedLang).build().getDisplayLanguage(Locale.getDefault()) }
                .getOrNull()?.replaceFirstChar { it.uppercase() } ?: langCode
        }
    }

    fun cleanSubtitleTitleAndLanguage(
        rawTitle: String?,
        videoName: String?,
        fallbackLang: String? = null,
        context: Context? = null
    ): Pair<String, String?> {
        val defaultSubtitleName = context?.getString(R.string.pref_subtitles) ?: "Subtitle"
        if (rawTitle.isNullOrBlank()) {
            val langName = fallbackLang?.let { getDisplayNameForLanguage(it, context) } ?: defaultSubtitleName
            return Pair(langName, fallbackLang)
        }

        var clean = rawTitle.substringBeforeLast('.').ifBlank { rawTitle }.trim()

        if (!videoName.isNullOrBlank()) {
            val videoBase = videoName.substringBeforeLast('.').trim()
            if (videoBase.isNotEmpty() && clean.startsWith(videoBase, ignoreCase = true)) {
                clean = clean.substring(videoBase.length)
            }
        }

        clean = clean.dropWhile { it in ".-_ " }

        var detectedLang = fallbackLang
        val lastDotIndex = clean.lastIndexOf('.')

        if (lastDotIndex != -1) {
            val segment = clean.substring(lastDotIndex + 1).trim()
            if (segment.length in 2..3 && segment.all { it.isLetter() }) {
                detectedLang = segment.lowercase(Locale.ROOT)
                clean = clean.substring(0, lastDotIndex).trim('.', '-', '_', ' ')
            }
        } else if (clean.length in 2..3 && clean.all { it.isLetter() }) {
            detectedLang = clean.lowercase(Locale.ROOT)
            clean = ""
        }

        val finalTitle = clean.ifBlank {
            detectedLang?.let { getDisplayNameForLanguage(it, context) } ?: defaultSubtitleName
        }

        return Pair(finalTitle, detectedLang)
    }

    fun parseExternalSubtitleMeta(
        subFile: File,
        videoFile: File,
        context: Context? = null
    ): SubtitleMeta {
        val rawName = subFile.name
        val lower = rawName.lowercase(Locale.ROOT)

        val isForced = lower.contains("forced") || lower.contains("forcé")
        val isHearingImpaired = lower.contains("sdh")

        val (rawCleanTitle, lang) = cleanSubtitleTitleAndLanguage(rawName, videoFile.name, context = context)
        val langDisplayName = getDisplayNameForLanguage(lang, context)
        val codecUpper = subFile.extension.uppercase(Locale.ROOT)

        val cleanTitle = if (rawCleanTitle.isNotBlank() && !rawCleanTitle.equals(langDisplayName, ignoreCase = true) && !rawCleanTitle.equals(subFile.extension, ignoreCase = true)) {
            rawCleanTitle
        } else {
            null
        }

        val displayTitle = formatSubtitleDisplayTitle(
            title = cleanTitle,
            languageDisplayName = langDisplayName,
            isForced = isForced,
            isHearingImpaired = isHearingImpaired,
            codecUpper = codecUpper,
            context = context
        )

        return SubtitleMeta(
            displayTitle = displayTitle,
            language = lang,
            isForced = isForced,
            isHearingImpaired = isHearingImpaired
        )
    }
}

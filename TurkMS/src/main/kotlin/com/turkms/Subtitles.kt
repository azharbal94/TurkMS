package com.turkms

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Arabic subtitles from two keyless/API-key services:
 *
 * 1. Wyzie Subs (`sub.wyzie.io`) when the user enters a free API key. It serves
 *    plain UTF-8 SRT/VTT download links and supports movie, season and episode
 *    lookups. The key is stored only in the local plugin settings.
 * 2. The keyless OpenSubtitles v3 Stremio addon, used as a fallback.
 *
 * Results are matched by IMDb id, so the subtitle belongs to the requested
 * movie/episode rather than a same-named release.
 */
object ArabicSubtitles {
    private const val STREMIO_BASE = "https://opensubtitles-v3.strem.io/subtitles"
    private const val WYZIE_BASE = "https://sub.wyzie.io"
    private val ARABIC_CODES = setOf("ara", "ar")
    private val PLAYABLE_FORMATS = setOf("srt", "vtt", "")
    private const val MAX_PER_SOURCE = 3
    private const val MAX_TOTAL = 5

    suspend fun find(
        imdbId: String?,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleFile> {
        if (!TurkMSSettings.arabicSubtitles) return emptyList()
        val id = normalizeImdb(imdbId) ?: return emptyList()
        if (isTv && (season == null || episode == null)) return emptyList()

        val used = mutableSetOf<String>()
        val result = mutableListOf<SubtitleFile>()

        // A user-provided Wyzie key gives the best coverage and plain SRT links.
        runCatching {
            findWyzie(id, isTv, season, episode)
        }.getOrDefault(emptyList()).forEach { subtitle ->
            if (result.size < MAX_TOTAL && used.add(subtitle.url)) result.add(subtitle)
        }

        runCatching {
            findStremio(id, isTv, season, episode)
        }.getOrDefault(emptyList()).forEach { subtitle ->
            if (result.size < MAX_TOTAL && used.add(subtitle.url)) result.add(subtitle)
        }

        return result
    }

    private suspend fun findWyzie(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val key = TurkMSSettings.wyzieApiKey.trim()
        if (key.isBlank()) return emptyList()

        val url = buildString {
            append(WYZIE_BASE)
            append("/search?id=")
            append(encode(id))
            append("&language=ar&format=srt,vtt&encoding=utf-8&limit=10&key=")
            append(encode(key))
            if (isTv) {
                append("&season=")
                append(season)
                append("&episode=")
                append(episode)
            }
        }

        val body = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to TURKMS_UA,
                ),
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        // /search returns a JSON array; accept a wrapped array as well so a
        // future API change does not silently disable subtitles.
        val items = runCatching {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data") ?: root.optJSONArray("subtitles")
                }

                else -> null
            }
        }.getOrNull() ?: return emptyList()

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            if (result.size >= MAX_PER_SOURCE) break
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("language").lowercase() !in ARABIC_CODES) continue
            if (item.optString("format").lowercase() !in PLAYABLE_FORMATS) continue
            val subUrl = item.optString("url")
            if (subUrl.isBlank() || !seen.add(subUrl)) continue
            if (isTv) {
                val marked = parseSeasonEpisode(item.optString("fileName"))
                if (marked != null && marked != season!! to episode!!) continue
            }
            result.add(newSubtitleFile(lang = "Arabic", url = subUrl))
        }
        return result
    }

    private suspend fun findStremio(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val url = if (isTv) {
            "$STREMIO_BASE/series/$id:$season:$episode.json"
        } else {
            "$STREMIO_BASE/movie/$id.json"
        }

        val body = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to TURKMS_UA,
                ),
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val subtitles = runCatching {
            JSONObject(body).optJSONArray("subtitles")
        }.getOrNull() ?: return emptyList()

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for (i in 0 until subtitles.length()) {
            if (result.size >= MAX_PER_SOURCE) break
            val item = subtitles.optJSONObject(i) ?: continue
            if (item.optString("lang").lowercase() !in ARABIC_CODES) continue
            val subUrl = item.optString("url")
            if (subUrl.isBlank() || !seen.add(subUrl)) continue

            // The addon sometimes tags every season with the requested season
            // number. Filenames such as "Se02.Ep001" reveal the real episode,
            // so drop an obvious mismatch instead of serving wrong subtitles.
            if (isTv) {
                val marked = parseSeasonEpisode(item.optString("subtitleFileName"))
                if (marked != null && marked != season!! to episode!!) continue
            }

            result.add(newSubtitleFile(lang = "Arabic", url = subUrl))
        }
        return result
    }

    private val EPISODE_PATTERNS = listOf(
        Regex("""(?i)\bS(?:e(?:ason)?)?[\s._-]*(\d{1,2})[\s._-]*E(?:p(?:isode)?)?[\s._-]*(\d{1,3})\b"""),
        Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b"""),
    )

    private fun parseSeasonEpisode(fileName: String): Pair<Int, Int>? {
        if (fileName.isBlank()) return null
        for (pattern in EPISODE_PATTERNS) {
            val match = pattern.find(fileName) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            return season to episode
        }
        return null
    }

    /** Accepts `tt123`, `123`, or a TMDB/IMDb id string. */
    fun normalizeImdb(imdbId: String?): String? {
        val raw = imdbId?.trim()?.removePrefix("tt") ?: return null
        if (raw.isBlank() || raw.any { !it.isDigit() }) return null
        return "tt$raw"
    }
}

package com.turkms

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import org.json.JSONObject

/**
 * Arabic subtitles from the keyless OpenSubtitles v3 addon.
 *
 * The addon returns pre-converted UTF-8 SRT links (not .gz), which is exactly
 * what CloudStream's player expects. Results are matched by IMDb id, so the
 * subtitle belongs to the same movie/episode rather than a same-named release.
 */
object ArabicSubtitles {
    private const val BASE = "https://opensubtitles-v3.strem.io/subtitles"
    private val ARABIC_CODES = setOf("ara", "ar")
    private const val MAX_RESULTS = 3

    suspend fun find(
        imdbId: String?,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleFile> {
        if (!TurkMSSettings.arabicSubtitles) return emptyList()
        val id = normalizeImdb(imdbId) ?: return emptyList()

        val url = if (isTv) {
            val s = season ?: return emptyList()
            val e = episode ?: return emptyList()
            "$BASE/series/$id:$s:$e.json"
        } else {
            "$BASE/movie/$id.json"
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
            if (result.size >= MAX_RESULTS) break
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

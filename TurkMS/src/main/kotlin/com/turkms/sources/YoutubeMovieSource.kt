package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.SiteDetails
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.decode
import com.turkms.encode
import com.turkms.extractYoutubeWithFallback
import com.turkms.fetchText
import com.turkms.normalizeTitle
import com.turkms.titleScore
import com.turkms.youtubeId
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube movie fallback.
 *
 * None of the scraped Turkish series sites publish films, so TMDB movies would
 * have metadata but nothing to play. YouTube carries a lot of full Turkish
 * movies on official channels, so we search it for long videos and hand the
 * watch URL to CloudStream's built-in YouTube extractor.
 */
class YoutubeMovieSource : SiteSource() {
    override val key = "youtube"
    override val name = "YouTube"
    override val mainUrl = "https://www.youtube.com"
    override val supportsMovies = true

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val searchQuery = "$query full film türkçe izle"
        val url = "$mainUrl/results?search_query=${encode(searchQuery)}&hl=tr&gl=TR"
        val html = fetchText(
            url,
            referer = "$mainUrl/",
            headers = mapOf(
                "Cookie" to "CONSENT=YES+cb; SOCS=CAI",
                "Accept-Language" to "tr-TR,tr;q=0.9",
            ),
        ) ?: return emptyList()

        val root = extractJson(html, "ytInitialData")?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return emptyList()

        val renderers = mutableListOf<JSONObject>()
        collectVideoRenderers(root, renderers, limit = 80)

        val results = mutableListOf<SiteResult>()
        val seen = mutableSetOf<String>()
        // A one-word title such as "Baba" can only contribute one matching
        // word (20 points), so do not require the two-word score for it.
        val meaningfulWords = normalizeTitle(query).split(" ").count { it.length > 2 }
        val requiredScore = if (meaningfulWords > 1) 40 else 20
        for (renderer in renderers) {
            val id = renderer.optString("videoId")
            if (id.isBlank() || !seen.add(id)) continue
            val title = renderer.optJSONObject("title")
                ?.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.trim()
                ?: continue
            val duration = durationSeconds(
                renderer.optJSONObject("lengthText")?.optString("simpleText")
            )
            // Movies are long; Shorts and clips would only add noise.
            if (duration < MIN_MOVIE_SECONDS) continue
            if (titleScore(title, query) < requiredScore) continue

            val poster = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            results.add(
                SiteResult(
                    name = title,
                    // The fragment keeps the title available without a second request.
                    url = "$mainUrl/watch?v=$id#t=${encode(title)}",
                    type = TvType.Movie,
                    poster = poster,
                )
            )
            if (results.size >= MAX_RESULTS) break
        }
        return results.sortedByDescending { titleScore(it.name, query) }
    }

    override suspend fun details(url: String): SiteDetails? {
        val id = youtubeId(url) ?: return null
        val title = url.substringAfter("#t=", "")
            .substringBefore("&")
            .let { decode(it) }
            .ifBlank { "YouTube videosu" }
        return SiteDetails(
            name = title,
            url = url,
            type = TvType.Movie,
            poster = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            plot = "Full-length movie published on YouTube.",
        )
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val id = youtubeId(url) ?: return false
        return extractYoutubeWithFallback(id, "$mainUrl/", callback)
    }

    private fun collectVideoRenderers(
        node: Any?,
        out: MutableList<JSONObject>,
        depth: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ) {
        if (node == null || depth > 30 || out.size >= limit) return
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { out.add(it) }
                val keys = node.keys()
                while (keys.hasNext() && out.size < limit) {
                    collectVideoRenderers(node.opt(keys.next()), out, depth + 1, limit)
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= limit) return
                    collectVideoRenderers(node.opt(i), out, depth + 1, limit)
                }
            }
        }
    }

    /** Finds `marker = { ... }` and returns the balanced JSON object. */
    private fun extractJson(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val open = html.indexOf('{', markerIndex)
        if (open < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until html.length) {
            val ch = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(open, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun durationSeconds(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val parts = text.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    private companion object {
        const val MIN_MOVIE_SECONDS = 20 * 60
        const val MAX_RESULTS = 8
    }
}

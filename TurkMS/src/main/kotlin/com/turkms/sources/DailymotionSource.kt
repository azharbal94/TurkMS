package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.turkms.SiteDetails
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.decode
import com.turkms.emitLink
import com.turkms.encode
import com.turkms.fetchText
import com.turkms.normalizeTitle
import com.turkms.parseYear
import com.turkms.titleScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Dailymotion movie fallback.
 *
 * Dailymotion has no browsable Turkish movie catalogue that is useful here, but
 * its public search API works without a key and it carries many full Turkish
 * films. Results are limited to long videos whose title matches the requested
 * movie, then playback uses the HLS manifest from the player metadata endpoint.
 */
class DailymotionSource : SiteSource() {
    override val key = "dailymotion"
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val supportsMovies = true
    override val supportsSeries = false

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()

        // Searching the bare title mostly returns trailers, reactions and news
        // clips. Full films are usually titled "... full film" or "... izle",
        // so query those variants in parallel and merge the results.
        val variants = listOf(query, "$query full", "$query full film", "$query izle")
        val items = coroutineScope {
            variants.map { term -> async { searchApi(term) } }.awaitAll().flatten()
        }

        // A one-word title such as "Baba" can only contribute one matching
        // word (20 points), so do not require the two-word score for it.
        val meaningfulWords = normalizeTitle(query).split(" ").count { it.length > 2 }
        val requiredScore = if (meaningfulWords > 1) 40 else 20

        val results = mutableListOf<SiteResult>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val id = item.optString("id").trim()
            if (id.isBlank() || !seen.add(id)) continue
            val title = item.optString("title").trim()
            if (title.isBlank()) continue
            // Movies are long; clips, trailers and highlights only add noise.
            if (item.optInt("duration") < MIN_MOVIE_SECONDS) continue
            if (titleScore(title, query) < requiredScore) continue

            val poster = item.optString("thumbnail_480_url")
                .takeIf { it.isNotBlank() }
                ?: "https://www.dailymotion.com/thumbnail/video/$id"
            results.add(
                SiteResult(
                    name = title,
                    // The fragment keeps the title available without a second request.
                    url = "$mainUrl/video/$id#t=${encode(title)}",
                    type = TvType.Movie,
                    poster = poster,
                )
            )
            if (results.size >= MAX_RESULTS) break
        }
        return results.sortedByDescending { titleScore(it.name, query) }
    }

    private suspend fun searchApi(term: String): List<JSONObject> {
        val api = "https://api.dailymotion.com/videos" +
            "?search=${encode(term)}" +
            "&fields=id,title,duration,thumbnail_480_url,url" +
            "&limit=40&sort=relevance"
        val text = fetchText(api, referer = "$mainUrl/") ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val list = json.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
    }

    override suspend fun details(url: String): SiteDetails? {
        val id = videoId(url) ?: return null
        val meta = fetchMetadata(id)
        val fragmentTitle = url.substringAfter("#t=", "")
            .substringBefore("&")
            .let { decode(it) }
        val title = meta?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: fragmentTitle.takeIf { it.isNotBlank() }
            ?: return null
        val poster = meta?.optString("thumbnail_url")?.takeIf { it.isNotBlank() }
            ?: "https://www.dailymotion.com/thumbnail/video/$id"
        return SiteDetails(
            name = title,
            url = url,
            type = TvType.Movie,
            poster = poster,
            plot = "Full-length movie published on Dailymotion.",
            year = parseYear(title),
        )
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val id = videoId(url) ?: return false
        val meta = fetchMetadata(id) ?: return false
        val qualities = meta.optJSONObject("qualities") ?: return false

        val links = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()
        val keys = qualities.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entries = qualities.optJSONArray(key) ?: continue
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val stream = entry.optString("url").trim()
                if (stream.isBlank() || !seen.add(stream)) continue
                val mime = entry.optString("type").lowercase()
                val isHls = mime.contains("mpegurl") || stream.contains(".m3u8")
                val label = when {
                    key.equals("auto", true) && isHls -> "Otomatik (HLS)"
                    key.equals("auto", true) -> "Otomatik"
                    else -> key
                }
                emitLink(
                    source = name,
                    label = label,
                    url = stream,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    referer = "$mainUrl/",
                    callback = { link ->
                        links.add(link)
                        callback(link)
                    },
                )
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun fetchMetadata(id: String): JSONObject? {
        val text = fetchText(
            url = "$mainUrl/player/metadata/video/$id",
            referer = "$mainUrl/",
        ) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun videoId(url: String): String? {
        val patterns = listOf(
            Regex("""dailymotion\.com/(?:video|embed/video)/([A-Za-z0-9]+)"""),
            Regex("""dai\.ly/([A-Za-z0-9]+)"""),
        )
        for (pattern in patterns) {
            pattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return null
    }

    private companion object {
        const val MIN_MOVIE_SECONDS = 20 * 60
        const val MAX_RESULTS = 8
    }
}

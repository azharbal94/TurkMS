package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.turkms.SiteDetails
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.absoluteUrl
import com.turkms.emitLink
import com.turkms.encode
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.firstImage
import com.turkms.parseYear
import com.turkms.postText
import org.json.JSONObject

/**
 * JetFilmizle (jetfilmizle.app).
 *
 * A Turkish movie portal with a normal paginated archive and an AI-assisted
 * search page. Playback is a POST to `/jetplayer` which returns one iframe per
 * source. The useful hosts are Videopark (its Vip and OPlay players expose a
 * JSON playlist), Odnoklassniki and StreamTape; trailers are YouTube and are
 * skipped.
 */
class JetFilmizleSource : SiteSource() {
    override val key = "jetfilmizle"
    override val name = "JetFilmizle"
    override val mainUrl = "https://jetfilmizle.app"
    override val supportsMovies = true
    override val supportsSeries = false

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val document = fetchDocument(
            "$mainUrl/arama?q=${encode(query)}",
            referer = "$mainUrl/",
        ) ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        val url = if (page == 1) "$mainUrl/filmler" else "$mainUrl/filmler/sayfa-$page"
        val document = fetchDocument(url, referer = "$mainUrl/") ?: return emptyList()
        return parseCards(document)
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (card in document.select("div.film-card")) {
            val link = card.selectFirst("a[href*='/film/']") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = card.selectFirst("h3.fc-title")?.text()
                ?.replace(Regex("\\s+"), " ")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: cleanTitle(link.attr("title"))
                ?: continue
            val year = card.selectFirst("span.fc-year")?.text()?.toIntOrNull()
                ?: parseYear(link.attr("title"))
            val poster = card.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            results.add(SiteResult(title, href, TvType.Movie, year, poster))
        }
        return results.distinctBy { it.url }
    }

    private fun cleanTitle(raw: String): String? {
        val value = raw
            .substringBefore(" Full")
            .substringBefore(" Türkçe")
            .substringBefore(" HD ")
            .replace(Regex("(?i)\\s+izle\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return value.takeIf { it.isNotBlank() }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val player = document.selectFirst("#active-player")

        val rawTitle = player?.attr("data-film-title")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.film-title")?.ownText()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" (")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val year = player?.attr("data-film-year")?.toIntOrNull()
            ?: parseYear(document.selectFirst("meta[property=og:title]")?.attr("content"))
            ?: parseYear(document.title())

        val poster = document.selectFirst("img.film-poster")
            ?.firstImage()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl(mainUrl, it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val plot = document.selectFirst("div.film-description div.description-text")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val score = document.selectFirst("a.imdb-badge b")
            ?.text()
            ?.replace(',', '.')
            ?.trim()
            ?.toDoubleOrNull()
            ?: player?.attr("data-film-rating")?.replace(',', '.')?.toDoubleOrNull()

        val tags = document.select("a.category-badge-details")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("a[href*='/oyuncu/']")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        return SiteDetails(
            name = rawTitle,
            url = url,
            type = TvType.Movie,
            poster = poster,
            plot = plot,
            year = year,
            tags = tags,
            actors = actors,
            score = score,
            episodes = emptyList(),
        )
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        val filmId = document.selectFirst("input[name=film_id]")
            ?.attr("value")
            ?.trim()
            .orEmpty()
        if (filmId.isBlank()) return false

        val links = mutableListOf<ExtractorLink>()
        val collector: suspend (ExtractorLink) -> Unit = { links.add(it) }
        val seen = mutableSetOf<String>()

        // The source buttons are duplicated for the dublaj and altyazili tabs;
        // both variants are requested because the alternate audio may expose
        // different hosts.
        for (button in document.select("button.player-source-btn[data-source-index]")) {
            val index = button.attr("data-source-index").toIntOrNull() ?: continue
            val playerType = button.attr("data-player-type").ifBlank { "dublaj" }
            if (!seen.add("$playerType:$index")) continue

            val html = postText(
                url = "$mainUrl/jetplayer",
                data = mapOf(
                    "film_id" to filmId,
                    "source_index" to index.toString(),
                    "player_type" to playerType,
                ),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            ) ?: continue

            val src = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""(?:src|file)\s*[:=]\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.getOrNull(1)
                ?: continue

            resolvePlayer(absoluteUrl(mainUrl, src.trim()), url, collector)
        }

        val unique = links.distinctBy { it.url }
        for (link in unique) callback(link)
        return unique.isNotEmpty()
    }

    private suspend fun resolvePlayer(
        playerUrl: String,
        pageUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        when {
            playerUrl.contains("videopark.top/oplayer") -> {
                val html = fetchText(playerUrl, referer = VIDEOPARK_REFERER) ?: return
                val data = extractJsonObject(html, "VIDEO_DATA") ?: return
                emitVideopark(data, playerUrl, callback)
            }

            playerUrl.contains("videopark.top/vip") -> {
                val html = fetchText(playerUrl, referer = VIDEOPARK_REFERER) ?: return
                val worker = findValue(html, """WORKER_URL\s*=\s*['"]([^'"]+)['"]""")
                val pubId = findValue(html, """PUB_ID\s*=\s*['"]([^'"]+)['"]""")
                val publisherId = findValue(html, """PUBLISHER_ID\s*=\s*['"]([^'"]+)['"]""")
                val videoId = findValue(html, """VIDEO_ID\s*=\s*['"]([^'"]+)['"]""")
                val title = findValue(html, """VIDEO_TITLE\s*=\s*['"]([^'"]+)['"]""")

                if (worker != null && pubId != null) {
                    var api = "$worker/api/stream?pubId=${encode(pubId)}" +
                        "&title=${encode(title ?: videoId ?: "")}"
                    if (!publisherId.isNullOrBlank()) {
                        api += "&publisherId=${encode(publisherId)}"
                    }
                    val text = fetchText(api, referer = playerUrl) ?: return
                    emitVideopark(text, playerUrl, callback)
                }
            }

            // The trailer button is never requested, but the source list can
            // still point at YouTube for a few films.
            playerUrl.contains("youtube", true) || playerUrl.contains("youtu.be", true) -> Unit

            else -> extractWithBuiltins(playerUrl, pageUrl, callback)
        }
    }

    private suspend fun emitVideopark(
        text: String,
        playerUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return

        val hls = json.optJSONObject("hlsSource")?.optString("file").orEmpty()
        if (hls.isNotBlank()) {
            emitLink(
                source = name,
                label = "HLS",
                url = hls,
                type = ExtractorLinkType.M3U8,
                referer = VIDEOPARK_REFERER,
                callback = callback,
            )
        }

        val sources = json.optJSONArray("mp4Sources")
            ?.takeIf { it.length() > 0 }
            ?: json.optJSONArray("sources")
        if (sources == null) return

        for (index in 0 until sources.length()) {
            val item = sources.optJSONObject(index) ?: continue
            val file = item.optString("file").trim()
            if (file.isBlank()) continue
            val type = item.optString("type")
            val isHls = type.contains("hls", true) || file.contains(".m3u8")
            val label = item.optString("label").trim().ifBlank {
                if (isHls) "HLS" else "Video"
            }
            emitLink(
                source = name,
                label = label,
                url = file,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = VIDEOPARK_REFERER,
                callback = callback,
            )
        }
    }

    /**
     * Videopark embeds its playlist as `const VIDEO_DATA = {...}` with escaped
     * slashes and nested objects, so it is read with a brace matcher instead of
     * a regular expression.
     */
    private fun extractJsonObject(html: String, marker: String): String? {
        val match = Regex(Regex.escape(marker) + """\s*=\s*""").find(html) ?: return null
        val start = match.range.last + 1
        if (start >= html.length || html[start] != '{') return null

        var depth = 0
        var inString = false
        var escaped = false
        var index = start
        while (index < html.length) {
            val char = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, index + 1)
                    }
                }
            }
            index++
        }
        return null
    }

    private fun findValue(html: String, pattern: String): String? =
        Regex(pattern).find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private companion object {
        const val VIDEOPARK_REFERER = "https://videopark.top/"
    }
}

package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.absoluteUrl
import com.turkms.cleanPlayerUrl
import com.turkms.emitLink
import com.turkms.encode
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.parseJwSources
import com.turkms.parseSeasonNumber
import com.turkms.parseYear
import com.turkms.postText
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * TvDiziler.tv.
 *
 * The site is a classic Turkish series portal. Its catalog is one large
 * alphabetical page, search is an AJAX endpoint returning escaped HTML, and
 * playback is either a Yandex Disk MP4 served through `/vid/ply/...` or a
 * spoox2 iframe whose real URL is resolved through `/ajax/service`.
 */
class TvDizilerSource : SiteSource() {
    override val key = "tvdiziler"
    override val name = "TvDiziler"
    override val mainUrl = "https://tvdiziler.tv"
    override val supportsMovies = true

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val text = postText(
            url = "$mainUrl/search?qr=${encode(query)}",
            data = emptyMap(),
            referer = "$mainUrl/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ) ?: return emptyList()

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val html = json.optString("data")
        if (html.isBlank()) return emptyList()
        return parseCards(Jsoup.parse(html, mainUrl))
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        val all = loadSeriesArchive()
        if (all.isEmpty()) return emptyList()
        val pageSize = PAGE_SIZE
        return all.drop((page - 1) * pageSize).take(pageSize)
    }

    @Volatile
    private var archiveCache: Pair<Long, List<SiteResult>>? = null

    private suspend fun loadSeriesArchive(): List<SiteResult> {
        val now = System.currentTimeMillis()
        archiveCache?.let { (time, items) ->
            if (now - time < ARCHIVE_TTL_MS) return items
        }
        // The complete series list is one alphabetically grouped page with no
        // numbered archive. Cache it and paginate locally.
        val document = withTimeoutOrNull(30_000) {
            fetchDocument("$mainUrl/dizi-izle")
        } ?: return emptyList()
        val items = parseCards(document)
        if (items.isNotEmpty()) archiveCache = now to items
        return items
    }

    private fun parseCards(document: Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (item in document.select("div.poster-md")) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            if (href.contains("/search") || href.contains("/ajax/")) continue

            val title = item.selectFirst("h3")?.text()?.trim()
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: item.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isBlank()) continue

            val poster = item.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            val type = when {
                href.contains("/dizi/") -> TvType.TvSeries
                href.contains("/film/") || href.contains("/film-") -> TvType.Movie
                else -> TvType.TvSeries
            }
            results.add(SiteResult(title, href, type, null, poster))
        }
        return results.distinctBy { it.url }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val h1 = document.selectFirst("h1")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim()
        val rawName = h1
            ?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            ?.replace(Regex("(?i)\\s+izle.*$"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("p[itemprop=name]")?.text()?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.series-profile-image img")?.firstImage()
                ?.let { absoluteUrl(mainUrl, it) }
        val plot = document.selectFirst("p[itemprop=description]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val actors = document.select("h5[itemprop=name]")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() && !it.equals(rawName, ignoreCase = true) }
            .distinct()

        val tags = document.selectFirst("div.series-profile-top")
            ?.select("a[href*='/tur/']")
            ?.map { it.text().replace(Regex("(?i)\\s+izle.*$"), "").trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

        val episodes = parseEpisodes(document)
        val type = when {
            episodes.isNotEmpty() -> TvType.TvSeries
            url.contains("/film/") || url.contains("/film-") -> TvType.Movie
            else -> TvType.TvSeries
        }

        return SiteDetails(
            name = rawName,
            url = url,
            type = type,
            poster = poster,
            plot = plot,
            year = parseYear(h1),
            tags = tags,
            actors = actors,
            episodes = episodes,
        )
    }

    private fun parseEpisodes(document: Document): List<SiteEpisode> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        // Every episode is one <li itemprop="season"> whose <meta itemprop="name">
        // is the season number. The season navigation has the same itemprop but
        // no episode anchor, so only nodes with a -bolum-izle link are kept.
        for (item in document.select("li[itemprop=season]")) {
            val anchors = item.select("a[href*='-bolum-izle']")
            if (anchors.isEmpty()) continue
            val season = item.selectFirst("meta[itemprop=name]")
                ?.attr("content")?.toIntOrNull()
                ?: parseSeasonNumber(item.text())
                ?: 1
            for (anchor in anchors) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                if (href.isBlank() || !seen.add(href)) continue
                val episode = anchor.selectFirst("data[itemprop=episodeNumber]")
                    ?.attr("content")?.toIntOrNull()
                    ?: parseEpisodeNumber(anchor.text())
                    ?: parseEpisodeNumber(href)
                val label = anchor.text()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "Bölüm ${episode ?: ""}" }
                val date = item.selectFirst("span.block")?.text()?.trim()
                result.add(
                    SiteEpisode(
                        name = label,
                        url = href,
                        season = season,
                        episode = episode,
                        date = date,
                    )
                )
            }
        }

        // Fallback for layouts that do not wrap episodes in the season item.
        if (result.isEmpty()) {
            for (anchor in document.select("a[href*='-bolum-izle']")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                if (href.isBlank() || !seen.add(href)) continue
                val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                if (label.isBlank()) continue
                result.add(
                    SiteEpisode(
                        name = label,
                        url = href,
                        season = parseSeasonNumber(anchor.text()) ?: 1,
                        episode = parseEpisodeNumber(anchor.text())
                            ?: parseEpisodeNumber(href),
                    )
                )
            }
        }

        return result.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        val links = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()
        val collector: suspend (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                links.add(link)
                callback(link)
            }
        }

        // "Tek Part" button: /vid/ply/<id> renders a JW player with a signed
        // Yandex Disk MP4.
        for (item in document.select("[data-hhs]")) {
            val value = item.attr("data-hhs").trim()
            if (value.isBlank()) continue
            val path = when {
                value.startsWith("http") -> value
                value.startsWith("/") -> value
                else -> "/vid/ply/$value"
            }
            val playerUrl = absoluteUrl(mainUrl, path)
            val html = fetchText(playerUrl, referer = url) ?: continue
            emitJwSources(html, playerUrl, url, collector)
            if (links.size >= 3) break
        }

        // Secondary player: encrypted spoox2 token resolved through an AJAX call.
        if (links.size < 3) {
            for (item in document.select("[data-spoox2]")) {
                val value = item.attr("data-spoox2").trim()
                if (value.isBlank()) continue
                loadSpoox(value, url, collector)
                if (links.size >= 3) break
            }
        }

        if (links.isEmpty()) {
            val iframe = findPlayerIframe(document)
            if (iframe != null) {
                extractWithBuiltins(absoluteUrl(mainUrl, iframe), url, collector)
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun loadSpoox(
        value: String,
        pageUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val text = postText(
            url = "$mainUrl/ajax/service",
            data = mapOf("vid" to value, "type" to "get_stream"),
            referer = pageUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ) ?: return
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!json.optBoolean("success")) return

        var uri = json.optString("uri")
        if (uri.isBlank()) return
        if (!uri.startsWith("http")) uri = absoluteUrl(mainUrl, uri)

        val wrapper = fetchText(uri, referer = pageUrl) ?: return
        val iframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(wrapper)?.groupValues?.getOrNull(1)
            ?: return
        val playerUrl = absoluteUrl(mainUrl, iframe)
        val playerHtml = fetchText(playerUrl, referer = uri) ?: return
        emitJwSources(playerHtml, playerUrl, pageUrl, callback)
    }

    private suspend fun emitJwSources(
        html: String,
        playerUrl: String,
        pageUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val sources = parseJwSources(html)
        if (sources.isEmpty()) return false
        var emitted = false
        for ((rawFile, label, type) in sources) {
            val file = cleanPlayerUrl(rawFile).replace("&amp;", "&")
            if (file.isBlank()) continue
            val stream = absoluteUrl(playerUrl, file)
            val isHls = type.contains("hls", true) || stream.contains(".m3u8")
            val isYandex = stream.contains("disk.yandex") || stream.contains("yandex.ru")
            emitLink(
                source = name,
                label = if (label.equals("auto", true)) "Otomatik" else label,
                url = stream,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                // Yandex Disk returns 403 when the tvdiziler Referer is attached.
                // An empty referer makes ExoPlayer send no Referer, which works.
                referer = if (isYandex) "" else pageUrl,
                callback = callback,
            )
            emitted = true
        }
        return emitted
    }

    private companion object {
        const val ARCHIVE_TTL_MS = 10 * 60 * 1000L
        const val PAGE_SIZE = 90
    }
}

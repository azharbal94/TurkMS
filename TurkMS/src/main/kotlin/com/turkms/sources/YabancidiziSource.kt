package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.TurkMSCache
import com.turkms.absoluteUrl
import com.turkms.cleanPlayerUrl
import com.turkms.emitLink
import com.turkms.encode
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.firstImage
import com.turkms.parseYear
import com.turkms.postText
import com.turkms.titleScore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Yabancı Dizi (yabancidizi.news).
 *
 * A large Turkish catalogue of foreign series and films. The site exposes a
 * JSON search endpoint for series, an alphabetical archive and a per-episode
 * download list with VidMoly ids. Episode pages render the real video through
 * `/ajax/service`, which currently answers HTTP 520, so the VidMoly mirrors are
 * decoded directly from the download links instead.
 */
class YabancidiziSource : SiteSource() {
    override val key = "yabancidizi"
    override val name = "Yabancı Dizi"
    override val mainUrl = "https://yabancidizi.news"
    override val supportsMovies = false
    override val supportsSeries = true

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val api = searchApi(query)
        if (api.isNotEmpty()) return api
        return loadArchive().asSequence()
            .map { it to titleScore(it.name, query) }
            .filter { it.second >= 40 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(30)
            .toList()
    }

    private suspend fun searchApi(query: String): List<SiteResult> {
        val text = postText(
            url = "$mainUrl/search?qr=${encode(query)}",
            data = mapOf("q" to query),
            referer = "$mainUrl/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ) ?: return emptyList()

        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val result = json.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        val items = mutableListOf<SiteResult>()
        for (index in 0 until result.length()) {
            val item = result.optJSONObject(index) ?: continue
            val link = item.optString("s_link").trim()
            if (link.isBlank()) continue
            val type = item.optString("s_type")
            // 0 is a series. Film results never come back from this endpoint.
            if (type.isNotBlank() && type != "0") continue
            val title = item.optString("s_name").trim()
            if (title.isBlank()) continue
            val href = absoluteUrl("$mainUrl/", "dizi/${link.trim('/')}")
            val year = item.optString("s_year").toIntOrNull() ?: parseYear(title)
            val image = item.optString("s_image").trim()
            val poster = when {
                image.isBlank() -> null
                image.startsWith("http") -> image
                else -> absoluteUrl("$mainUrl/", "/uploads/series/$image")
            }
            items.add(SiteResult(title, href, TvType.TvSeries, year, poster))
        }
        return items.distinctBy { it.url }
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        if (page == 1) {
            val home = withTimeoutOrNull(30_000) { fetchDocument("$mainUrl/") }
            val items = home?.let { parseHomeCards(it) }.orEmpty()
            if (items.isNotEmpty()) return items
        }
        val archive = loadArchive()
        if (archive.isEmpty()) return emptyList()
        val offset = if (page <= 2) 0 else (page - 2) * PAGE_SIZE
        return archive.drop(offset).take(PAGE_SIZE)
    }

    private fun parseHomeCards(document: Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (anchor in document.select("a[href^=dizi/][title]")) {
            val rawHref = anchor.attr("href")
            // Home sliders link straight to a season; trim it back to the show.
            val seriesPath = rawHref.substringBefore("/sezon-")
            val href = absoluteUrl("$mainUrl/", seriesPath)
            if (href.isBlank()) continue
            val title = cleanTitle(anchor.attr("title").ifBlank { anchor.text() })
            if (title.isBlank()) continue
            val poster = anchor.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl("$mainUrl/", it) }
            val year = parseYear(anchor.attr("title"))
            results.add(SiteResult(title, href, TvType.TvSeries, year, poster))
        }
        return results.distinctBy { it.url }
    }

    private suspend fun loadArchive(): List<SiteResult> {
        return TurkMSCache.getOrPut(ARCHIVE_KEY) {
            val document = withTimeoutOrNull(45_000) {
                fetchDocument("$mainUrl/dizi-izle-hd")
            } ?: return@getOrPut null
            val items = mutableListOf<SiteResult>()
            for (anchor in document.select("a[href^=dizi/][title]")) {
                val href = absoluteUrl("$mainUrl/", anchor.attr("href"))
                if (href.isBlank()) continue
                val title = cleanTitle(anchor.attr("title"))
                if (title.isBlank()) continue
                val poster = anchor.selectFirst("img")
                    ?.firstImage()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl("$mainUrl/", it) }
                items.add(SiteResult(title, href, TvType.TvSeries, null, poster))
            }
            items.distinctBy { it.url }.ifEmpty { null }
        }.orEmpty()
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)\\s+izle\\s*$"), "")
            .replace(Regex("(?i)\\s+\\d+\\.\\s*sezon\\s*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val heading = document.selectFirst("h1.page-title")
        val rawName = heading?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" (")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val name = rawName
            .replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")
            .replace(Regex("(?i)\\s+izle\\s*$"), "")
            .trim()

        val poster = document.selectFirst("img.series-profile-thumb")
            ?.firstImage()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl("$mainUrl/", it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val plot = document.selectFirst("p#tv-series-desc")
            ?.text()
            ?.replace(Regex("(?i)\\s*\\.\\.\\.\\s*Devam[ıi]n[ıi]\\s+G[öo]ster\\s*$"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val year = parseYear(document.selectFirst("div.media-meta")?.text())
            ?: parseYear(rawName)

        val score = document.selectFirst("div.color-imdb")
            ?.text()
            ?.replace(',', '.')
            ?.trim()
            ?.toDoubleOrNull()

        val tags = document.select("div.item a[href*='/tur/']")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("div.item a[href*='/oyuncu/']")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document)
        val type = if (episodes.isNotEmpty()) TvType.TvSeries else TvType.Movie

        return SiteDetails(
            name = name,
            url = url,
            type = type,
            poster = poster,
            plot = plot,
            year = year,
            tags = tags,
            actors = actors,
            score = score,
            episodes = episodes,
        )
    }

    private fun parseEpisodes(document: Document): List<SiteEpisode> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (anchor in document.select("a[href*='/sezon-'][href*='/bolum-']")) {
            val href = absoluteUrl("$mainUrl/", anchor.attr("href"))
            if (href.isBlank() || !seen.add(href)) continue
            val match = Regex("""sezon-(\d+)/bolum-(\d+)""").find(href) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            val label = anchor.text()
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Sezon $season Bölüm $episode" }
            result.add(
                SiteEpisode(
                    name = label,
                    url = href,
                    season = season,
                    episode = episode,
                )
            )
        }
        return result.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        val links = mutableListOf<ExtractorLink>()
        val collector: suspend (ExtractorLink) -> Unit = { links.add(it) }

        // Every episode page lists its VidMoly uploads as download links. The
        // id in `/dl/<id>` is the same id used by the embed player.
        val html = document.outerHtml()
        val ids = Regex("""vidmoly\.me/dl/([A-Za-z0-9]+)""")
            .findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(6)
            .toList()
        for (id in ids) {
            val embed = "https://vidmoly.biz/embed-$id.html"
            extractWithBuiltins(embed, url, collector)
        }

        // The native player endpoint is currently down (HTTP 520), but if it
        // starts answering again it exposes extra mirrors, including for films.
        if (links.isEmpty()) {
            loadFromAjax(document, url, collector)
        }

        val unique = links.distinctBy { it.url }
        for (link in unique) callback(link)
        return unique.isNotEmpty()
    }

    private suspend fun loadFromAjax(
        document: Document,
        pageUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val token = document.selectFirst("#not-loaded[data-whatwehave]")
            ?.attr("data-whatwehave")
            .orEmpty()
        if (token.isNotBlank()) {
            val text = postText(
                url = "$mainUrl/ajax/service",
                data = mapOf("e_id" to token, "v_lang" to "en", "type" to "get_whatwehave"),
                referer = pageUrl,
                headers = XHR_HEADERS,
            )
            if (!text.isNullOrBlank() && !text.startsWith("error")) {
                emitAjaxJson(text, pageUrl, callback)
            }
        }

        val item = document.selectFirst("[data-link][data-hash]") ?: return
        val text = postText(
            url = "$mainUrl/ajax/service",
            data = mapOf(
                "link" to item.attr("data-link"),
                "hash" to item.attr("data-hash"),
                "querytype" to item.attr("data-querytype").ifBlank { "alternate" },
                "type" to "videoGet",
            ),
            referer = pageUrl,
            headers = XHR_HEADERS,
        ) ?: return
        if (!text.startsWith("error")) emitAjaxJson(text, pageUrl, callback)
    }

    private suspend fun emitAjaxJson(
        text: String,
        pageUrl: String,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val iframe = sequenceOf(
            json?.optString("api_iframe").orEmpty(),
            json?.optJSONObject("api")?.optString("api_iframe").orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        if (iframe.isNotBlank()) {
            if (!iframe.contains("youtube", true)) {
                extractWithBuiltins(absoluteUrl("$mainUrl/", iframe), pageUrl, callback)
            }
            return
        }

        // Some responses carry the stream URLs directly instead of an iframe.
        val cleaned = cleanPlayerUrl(text)
        for (match in Regex("""https?://[^"'\s\\]+\.(?:m3u8|mp4)[^"'\s\\]*""").findAll(cleaned)) {
            val stream = match.value
            val type = if (stream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            emitLink(
                source = name,
                label = if (type == ExtractorLinkType.M3U8) "HLS" else "Video",
                url = stream,
                type = type,
                referer = pageUrl,
                callback = callback,
            )
        }
    }

    private companion object {
        const val ARCHIVE_KEY = "yabancidizi:archive"
        const val PAGE_SIZE = 90
        val XHR_HEADERS = mapOf("X-Requested-With" to "XMLHttpRequest")
    }
}

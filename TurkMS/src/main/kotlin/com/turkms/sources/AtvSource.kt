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
import com.turkms.emitLink
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.titleScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * atv (atv.com.tr).
 *
 * The series grid on `/diziler` and the classics grid on `/eski-diziler` both
 * use `div.category-classic-item` cards. A series page links its episodes as
 * `/{slug}/{n}-bolum/izle`; those pages publish a 1080p MP4 on `erbvr.com` in
 * the JSON-LD `VideoObject.contentUrl`.
 */
class AtvSource : SiteSource() {
    override val key = "atv"
    override val name = "atv"
    override val mainUrl = "https://www.atv.com.tr"
    override val supportsMovies = false

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        return loadArchive().asSequence()
            .map { it to titleScore(it.name, query) }
            .filter { it.second >= 40 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(30)
            .toList()
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        val archive = loadArchive()
        if (archive.isEmpty()) return emptyList()
        return archive.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
    }

    private suspend fun loadArchive(): List<SiteResult> {
        return TurkMSCache.getOrPut(ARCHIVE_KEY) {
            // Both grids are independent; fetch them together to stay inside
            // the aggregator's search timeout.
            val documents = coroutineScope {
                listOf("/diziler", "/eski-diziler").map { path ->
                    async { withTimeoutOrNull(25_000) { fetchDocument("$mainUrl$path") } }
                }.awaitAll()
            }

            val results = mutableListOf<SiteResult>()
            for (document in documents) {
                if (document != null) parseCards(document, results)
            }
            val items = results.distinctBy { it.url }
            if (items.isEmpty()) null else items
        }.orEmpty()
    }

    private fun parseCards(document: Document, target: MutableList<SiteResult>) {
        val cards = document.select("div.category-classic-item a[href], div.category-item a[href]")
        for (anchor in cards) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank()) continue
            val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().trimEnd('/')
            if (path.isEmpty() || path.count { it == '/' } != 1) continue
            if (path in NON_SERIES_PATHS) continue

            val img = anchor.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.text().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
                ?: titleFromSlug(href)
            if (title.isBlank()) continue

            val poster = img?.firstImage()?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            target.add(SiteResult(title, href, TvType.TvSeries, poster = poster))
        }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val jsonName = jsonLdString(document, "TVSeries", "name")
        val name = jsonName
            ?: document.firstText("h1")
                ?.replace(Regex("(?i)\\s+dizisi\\s*$"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val poster = jsonLdImage(document)
            ?: document.select("img[alt]")
                .firstOrNull { it.attr("alt").trim().equals(name, true) }
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            ?: document.metaContent("og:image")

        val plot = document.metaContent("og:description", "description")
        val episodes = parseEpisodes(url, document)

        return SiteDetails(
            name = name,
            url = url,
            type = TvType.TvSeries,
            poster = poster,
            plot = plot,
            episodes = episodes,
        )
    }

    private suspend fun parseEpisodes(seriesUrl: String, landing: Document): List<SiteEpisode> {
        val path = runCatching { java.net.URI(seriesUrl).path }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val listPage = withTimeoutOrNull(20_000) {
            fetchDocument("$mainUrl$path/bolumler", referer = seriesUrl)
        }

        val pattern = Regex("""$path/(\d+)-bolum/izle/?$""")
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (page in listOfNotNull(landing, listPage)) {
            collectEpisodes(page, pattern, seen, result)
        }

        // The /bolumler page only renders the first screenful. It exposes the
        // category id and cursor for `/ajax/series/infinityvideolist`, which
        // returns the remaining episodes as an HTML fragment.
        val listHtml = listPage?.html().orEmpty()
        val categoryId = Regex("""_categoryId\s*=\s*['"]([^'"]+)['"]""")
            .find(listHtml)?.groupValues?.getOrNull(1)
        val firstPage = Regex("""_pageIndex\s*=\s*(\d+)""")
            .find(listHtml)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (!categoryId.isNullOrBlank() && firstPage != null) {
            val takeCount = Regex("""_takeCount\s*=\s*(\d+)""")
                .find(listHtml)?.groupValues?.getOrNull(1) ?: "13"
            val videoType = Regex("""_videoType\s*=\s*(\d+)""")
                .find(listHtml)?.groupValues?.getOrNull(1) ?: "10"
            val orderBy = Regex("""_orderBy\s*=\s*(\d+)""")
                .find(listHtml)?.groupValues?.getOrNull(1) ?: "4"
            val articleType = Regex("""_articleType\s*=\s*(\d+)""")
                .find(listHtml)?.groupValues?.getOrNull(1) ?: "10"

            var page = firstPage
            var emptyRuns = 0
            while (page < firstPage + MAX_AJAX_PAGES && emptyRuns < 2) {
                val url = "$mainUrl/ajax/series/infinityvideolist" +
                    "?pageindex=$page&takecount=$takeCount" +
                    "&categoryid=$categoryId&videotype=$videoType" +
                    "&orderby=$orderBy&articletype=$articleType"
                val body = withTimeoutOrNull(12_000) {
                    fetchText(
                        url,
                        referer = "$mainUrl$path/bolumler",
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    )
                }
                val html = runCatching {
                    JSONObject(body.orEmpty()).optString("html")
                }.getOrNull().orEmpty()
                if (html.isBlank()) {
                    emptyRuns++
                } else {
                    val before = result.size
                    val fragment = org.jsoup.Jsoup.parse(html)
                    collectEpisodes(fragment, pattern, seen, result)
                    if (result.size == before) emptyRuns++ else emptyRuns = 0
                }
                page++
            }
        }
        return result.sortedBy { it.episode ?: 0 }
    }

    private fun collectEpisodes(
        page: Document,
        pattern: Regex,
        seen: MutableSet<String>,
        target: MutableList<SiteEpisode>,
    ) {
        for (anchor in page.select("a[href]")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank() || href.contains("fragman")) continue
            val match = pattern.find(href) ?: continue
            val number = match.groupValues[1].toIntOrNull()
                ?: parseEpisodeNumber(href)
                ?: continue
            if (!seen.add(href)) continue
            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                .ifBlank { "$number. Bölüm" }
            target.add(
                SiteEpisode(
                    name = label,
                    url = href,
                    season = 1,
                    episode = number,
                )
            )
        }
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        val html = document.html()
        val stream = jsonLdContentUrls(document).firstOrNull { it.contains(".mp4") }
            ?: jsonLdContentUrls(document).firstOrNull { it.contains(".m3u8") }
            ?: Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(html)?.value
            ?: Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.value
            ?: return false

        val isHls = stream.contains(".m3u8")
        emitLink(
            source = name,
            label = if (isHls) "HLS" else qualityLabel(stream),
            url = stream,
            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            referer = url,
            callback = callback,
        )
        return true
    }

    private fun qualityLabel(url: String): String =
        when {
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            else -> "Video"
        }

    private companion object {
        const val ARCHIVE_KEY = "atv:archive"
        const val PAGE_SIZE = 60
        const val MAX_AJAX_PAGES = 12
        val NON_SERIES_PATHS = setOf(
            "/diziler", "/eski-diziler", "/programlar", "/canli-yayin", "/yayin-akisi",
            "/dizi-tv", "/rss-bilgi", "/adblock", "/fragmanlar",
        )
    }
}

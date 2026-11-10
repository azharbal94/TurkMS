package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.TurkMSCache
import com.turkms.absoluteUrl
import com.turkms.fetchDocument
import com.turkms.titleScore
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * izle7 (izle7.com) — Kanal 7's on-demand portal.
 *
 * `/kanal7/diziler` lists one category card per show. A category page
 * (`/kanal7/kategori/<slug>`) lists its episodes as
 * `/kanal7/izle-<id>-<slug>-<n>-bolum-<date>.html` across a handful of pages.
 * Episode JSON-LD points at a Dailymotion crawler URL, so playback reuses the
 * existing Dailymotion HLS extractor.
 */
class Izle7Source : SiteSource() {
    override val key = "izle7"
    override val name = "izle7"
    override val mainUrl = "https://www.izle7.com"
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
            val document = withTimeoutOrNull(25_000) { fetchDocument("$mainUrl/kanal7/diziler") }
                ?: return@getOrPut null
            val results = mutableListOf<SiteResult>()
            for (anchor in document.select("a[href*='/kanal7/kategori/']")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                    .substringBefore("?")
                    .trimEnd('/')
                if (href.isBlank()) continue
                val title = anchor.selectFirst(".title")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: anchor.attr("title")
                        ?.removeSuffix(" Dizisi")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: titleFromSlug(href)
                if (title.isBlank()) continue

                val img: org.jsoup.nodes.Element? =
                    anchor.selectFirst("img[data-original], img[data-src], img")
                var rawPoster = img?.attr("data-original").orEmpty()
                if (rawPoster.isBlank()) rawPoster = img?.attr("data-src").orEmpty()
                if (rawPoster.isBlank()) rawPoster = img?.attr("src").orEmpty()
                val poster = if (rawPoster.isBlank()) {
                    null
                } else {
                    absoluteUrl(mainUrl, rawPoster)
                }
                results.add(SiteResult(title, href, TvType.TvSeries, poster = poster))
            }
            results.distinctBy { it.url }.ifEmpty { null }
        }.orEmpty()
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val name = document.metaContent("og:title")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("izle7", true) }
            ?: document.selectFirst("a[href*='/kategori/'] .title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val posterImg: org.jsoup.nodes.Element? =
            document.selectFirst("img[alt='$name Dizisi']")
        val poster: String? = if (posterImg != null) {
            var raw = posterImg.attr("data-original")
            if (raw.isBlank()) raw = posterImg.attr("data-src")
            if (raw.isBlank()) raw = posterImg.attr("src")
            if (raw.isBlank()) null else absoluteUrl(mainUrl, raw)
        } else {
            null
        }
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

    private suspend fun parseEpisodes(url: String, landing: Document): List<SiteEpisode> {
        val base = url.substringBefore("?").trimEnd('/')
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        parseEpisodePage(landing, seen, result)

        val maxPage = landing.select("a[href*='?page=']")
            .mapNotNull { Regex("""[?&]page=(\d+)""").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?.coerceAtMost(MAX_PAGES)
            ?: 1
        for (page in 2..maxPage) {
            val document = withTimeoutOrNull(15_000) {
                fetchDocument("$base?page=$page", referer = url)
            } ?: continue
            parseEpisodePage(document, seen, result)
        }

        return result.sortedBy { it.episode ?: 0 }
    }

    private fun parseEpisodePage(
        document: Document,
        seen: MutableSet<String>,
        target: MutableList<SiteEpisode>,
    ) {
        for (anchor in document.select("a[href*='/kanal7/izle-']")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
                .substringBefore("?")
                .trimEnd('/')
            if (href.isBlank() || !seen.add(href)) continue
            val number = Regex("""-(\d+)-bolum-""").find(href)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val label = anchor.selectFirst(".category-item-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "$number. Bölüm"
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
        val candidates = jsonLdContentUrls(document) +
            Regex("""https?://(?:www\.)?dailymotion\.com/(?:crawler/)?video/([A-Za-z0-9]+)""")
                .findAll(document.html())
                .map { it.value }
                .toList()

        for (candidate in candidates) {
            val id = Regex("""dailymotion\.com/(?:crawler/)?video/([A-Za-z0-9]+)""")
                .find(candidate)?.groupValues?.getOrNull(1)
                ?: continue
            val direct = "https://www.dailymotion.com/video/$id"
            if (DailymotionSource().loadLinks(direct, callback)) return true
        }
        return false
    }

    private companion object {
        const val ARCHIVE_KEY = "izle7:archive"
        const val PAGE_SIZE = 60
        const val MAX_PAGES = 20
    }
}

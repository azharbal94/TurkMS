package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.TurkMSCache
import com.turkms.absoluteUrl
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.parseSeasonNumber
import com.turkms.parseYear
import com.turkms.titleScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * DiziBOX (dizibox.live).
 *
 * The site is a WordPress series portal. Its complete catalog is one
 * alphabetical page (~4,900 shows), the home page highlights new/popular shows
 * and every season page lists its own episodes. Playback offers three players:
 * a Turkey-only one, a VidMoly wrapper and an Odnoklassniki wrapper. The last
 * two are decoded here and handed to CloudStream's bundled extractors.
 */
class DiziboxSource : SiteSource() {
    override val key = "dizibox"
    override val name = "Dizibox"
    override val mainUrl = "https://www.dizibox.live"
    override val supportsMovies = false

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val archive = loadArchive()
        if (archive.isEmpty()) return emptyList()
        return archive.asSequence()
            .map { it to titleScore(it.name, query) }
            .filter { it.second >= 40 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(30)
            .toList()
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        if (page == 1) {
            val home = fetchDocument("$mainUrl/") ?: return emptyList()
            val items = parseHomeCards(home)
            if (items.isNotEmpty()) return items
        }
        // The archive is alphabetical, but it is the only paginated list the
        // site exposes. It keeps "load more" working after the home page.
        val archive = loadArchive()
        if (archive.isEmpty()) return emptyList()
        return archive.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
    }

    private suspend fun loadArchive(): List<SiteResult> {
        return TurkMSCache.getOrPut(ARCHIVE_KEY) {
            val document = withTimeoutOrNull(45_000) { fetchDocument("$mainUrl/diziler/") }
                ?: return@getOrPut null
            val results = mutableListOf<SiteResult>()
            for (anchor in document.select("ul.alphabetical-category-list a[href*='/diziler/']")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                if (href.isBlank()) continue
                val title = anchor.attr("title")
                    .ifBlank { anchor.text() }
                    .replace(Regex("(?i)\\s+izle\\s*$"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (title.isBlank()) continue
                results.add(SiteResult(title, href, TvType.TvSeries))
            }
            if (results.isEmpty()) null else results.distinctBy { it.url }
        }.orEmpty()
    }

    private fun parseHomeCards(document: Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()

        // Highlighted new series: clean titles and lazy-loaded posters.
        for (item in document.select("#new-series article")) {
            val link = item.selectFirst("a[href*='/diziler/']") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = item.selectFirst("a.poster-title")?.text()
                ?.ifBlank { link.attr("title") }
                ?.ifBlank { item.selectFirst("img")?.attr("alt") }
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: titleFromUrl(href)
            val poster = item.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            val year = parseYear(item.text())
            results.add(SiteResult(title, href, TvType.TvSeries, year, poster))
        }

        // Best / recommended small cards show their title in `div.tv-title`.
        val smallSelectors = listOf("#best-series article", "#recommended-series article")
        for (selector in smallSelectors) {
            for (item in document.select(selector)) {
                val link = item.selectFirst("a[href*='/diziler/']") ?: continue
                val href = absoluteUrl(mainUrl, link.attr("href"))
                if (href.isBlank()) continue
                val title = item.selectFirst("div.tv-title")?.text()
                    ?.replace(Regex("(?i)\\s+izle\\s*$"), "")
                    ?.replace(Regex("\\s+"), " ")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: item.selectFirst("img")?.attr("alt")
                        ?.replace(Regex("\\s+"), " ")?.trim()
                        ?.takeIf { it.isNotBlank() }
                    ?: titleFromUrl(href)
                val poster = item.selectFirst("img")
                    ?.firstImage()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(mainUrl, it) }
                results.add(SiteResult(title, href, TvType.TvSeries, null, poster))
            }
        }

        return results.distinctBy { it.url }
    }

    private fun titleFromUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast('/')
            .replace(Regex("(?i)-izle.*$"), "")
            .replace(Regex("(?i)-hd.*$"), "")
            .replace('-', ' ')
            .trim()
        if (slug.isBlank()) return "Dizi"
        return slug.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val rawTitle = document.selectFirst("#single-diziler h1 a, #single-diziler h1, h1 a, h1")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title()
                .substringBefore(" izle")
                .substringBefore("|")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("figure#main-cover img.main-cover, figure#main-cover img, img.main-cover")
            ?.firstImage()
            ?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl(mainUrl, it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val plot = document.selectFirst("div.tv-story p, div.tv-story")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")
                ?.takeIf { it.isNotBlank() }

        val year = parseYear(document.selectFirst("div.terms a[href*='/yil/']")?.text())
            ?: parseYear(document.title())

        val score = document.selectFirst("span.label-imdb b")
            ?.text()
            ?.replace(',', '.')
            ?.trim()
            ?.toDoubleOrNull()

        val tags = document.select("div.terms a[href*='/tur/']")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("div.terms a[href*='/oyuncu/']")
            .map { it.text().replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodes = parseEpisodes(document, url)

        return SiteDetails(
            name = rawTitle,
            url = url,
            type = TvType.TvSeries,
            poster = poster,
            plot = plot,
            year = year,
            tags = tags,
            actors = actors,
            score = score,
            episodes = episodes,
        )
    }

    /**
     * The series landing page only renders the first (currently selected)
     * season. Every other season lives on its own `/dizi/<slug>/N-sezon-...`
     * page, so those pages are fetched in small parallel batches.
     */
    private suspend fun parseEpisodes(document: Document, seriesUrl: String): List<SiteEpisode> {
        val seasonLinks = document.select("#seasons-list a[href]")
            .map { absoluteUrl(mainUrl, it.attr("href")) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEASONS)

        val pages = mutableListOf<Document>()
        pages.add(document)
        // Season pages are large (~800 KB each) and independent, so fetch them
        // all at once instead of one batch after another.
        if (seasonLinks.isNotEmpty()) {
            val fetched = coroutineScope {
                seasonLinks.map { link ->
                    async {
                        withTimeoutOrNull(11_000) { fetchDocument(link, referer = seriesUrl) }
                    }
                }.awaitAll().filterNotNull()
            }
            pages.addAll(fetched)
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        fun collect(requireClass: Boolean) {
            val selector = if (requireClass) {
                "a.season-episode[href]"
            } else {
                "a[href*='-bolum-izle']"
            }
            for (page in pages) {
                for (anchor in page.select(selector)) {
                    val href = absoluteUrl(mainUrl, anchor.attr("href"))
                    if (href.isBlank() || !seen.add(href)) continue
                    val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                    if (!label.contains("bölüm", true) && !href.contains("-bolum-izle")) continue
                    val season = parseSeasonNumber(label)
                        ?: parseSeasonNumber(href)
                        ?: 1
                    val episode = parseEpisodeNumber(label)
                        ?: parseEpisodeNumber(href)
                    val name = label.ifBlank { "Sezon $season Bölüm ${episode ?: ""}" }
                    result.add(
                        SiteEpisode(
                            name = name,
                            url = href,
                            season = season,
                            episode = episode,
                        )
                    )
                }
            }
        }

        collect(requireClass = true)
        if (result.isEmpty()) {
            seen.clear()
            collect(requireClass = false)
        }
        return result.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val links = mutableListOf<ExtractorLink>()
        val seen = mutableSetOf<String>()
        val collector: suspend (ExtractorLink) -> Unit = { link ->
            if (seen.add(link.url)) {
                links.add(link)
                callback(link)
            }
        }

        val firstPage = fetchDocument(url)
        if (firstPage != null) handlePage(url, firstPage, collector)

        // Turkey-only "DBX Pro" player is skipped; alternating players are
        // exposed as /2/ and /3/ variants of the same episode URL.
        if (links.isEmpty()) {
            for (index in 2..3) {
                val alternate = alternateUrl(url, index)
                if (alternate == url) continue
                val document = fetchDocument(alternate, referer = url) ?: continue
                handlePage(alternate, document, collector)
                if (links.isNotEmpty()) break
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun handlePage(
        pageUrl: String,
        document: Document,
        callback: suspend (ExtractorLink) -> Unit,
    ) {
        val iframe = findPlayerIframe(document) ?: return
        val playerUrl = absoluteUrl(mainUrl, iframe)
        if (playerUrl.isBlank()) return
        when {
            // "DBX Pro" is geo-restricted to Turkey; there is no stream to get.
            playerUrl.contains("/player/king/") -> Unit
            playerUrl.contains("/player/moly/") -> decodeMoly(playerUrl, pageUrl, callback)
            playerUrl.contains("/player/haydi") -> decodeHaydi(playerUrl, pageUrl, callback)
            else -> extractWithBuiltins(playerUrl, pageUrl, callback)
        }
    }

    /**
     * VidMoly wrapper: `document.write(atob(unescape("<percent-encoded base64>")))`.
     * The decoded HTML contains the real `vidmoly.biz/embed-...` iframe.
     */
    private suspend fun decodeMoly(
        playerUrl: String,
        referer: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val html = fetchText(playerUrl, referer = referer) ?: return false
        val encoded = Regex("""unescape\s*\(\s*["']([^"']+)["']\s*\)""")
            .find(html)?.groupValues?.getOrNull(1)
        val decoded = encoded?.let {
            runCatching {
                val percent = java.net.URLDecoder.decode(it, "UTF-8")
                val bytes = android.util.Base64.decode(percent, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8)
            }.getOrNull()
        }
        val target = decoded?.let { inner ->
            Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                .find(inner)?.groupValues?.getOrNull(1)
        } ?: Regex("""(?:https?:)?//[^"'\s<>]*vidmoly[^"'\s<>]*""")
            .find(html)?.value
        if (target.isNullOrBlank()) return false
        return extractWithBuiltins(absoluteUrl(playerUrl, target.trim()), referer, callback)
    }

    /** Odnoklassniki wrapper: the `v` query parameter is a base64 URL. */
    private suspend fun decodeHaydi(
        playerUrl: String,
        referer: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val value = Regex("""[?&]v=([^&]+)""")
            .find(playerUrl)?.groupValues?.getOrNull(1) ?: return false
        val target = runCatching {
            val bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return false
        if (!target.startsWith("http")) return false
        return extractWithBuiltins(target, referer, callback)
    }

    private fun alternateUrl(url: String, index: Int): String {
        val clean = url.substringBefore("?").substringBefore("#")
        return if (clean.endsWith("/")) "$clean$index/" else "$clean/$index/"
    }

    private companion object {
        const val ARCHIVE_KEY = "dizibox:archive"
        const val PAGE_SIZE = 60
        const val MAX_SEASONS = 20
    }
}

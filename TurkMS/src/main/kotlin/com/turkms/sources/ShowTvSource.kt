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
import com.turkms.titleScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * Show TV (showtv.com.tr).
 *
 * `/diziler` lists the running shows and `/diziler/arsivdeki-diziler` the
 * archive; both link to `/dizi/tanitim/<slug>/<id>`. A series page embeds every
 * episode link as `/dizi/tum_bolumler/<slug>-sezon-<s>-bolum-<e>-izle/<id>`.
 * Episodes carry a 1080p MP4 in the JSON-LD `contentUrl` and an adaptive HLS
 * manifest in the embedded player configuration.
 */
class ShowTvSource : SiteSource() {
    override val key = "showtv"
    override val name = "Show TV"
    override val mainUrl = "https://www.showtv.com.tr"
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
            // Fetch both grids at once; sequentially they take too long for the
            // aggregator's search budget.
            val documents = coroutineScope {
                listOf("/diziler", "/diziler/arsivdeki-diziler").map { path ->
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
        for (anchor in document.select("a[href*='/dizi/tanitim/']")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank()) continue
            val img = anchor.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: anchor.text().replace(Regex("\\s+"), " ").trim().takeIf { it.isNotBlank() }
                ?: titleFromSlug(href).takeIf { it.isNotBlank() }
                ?: continue
            val poster = img?.firstImage()?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            target.add(SiteResult(title, href, TvType.TvSeries, poster = poster))
        }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val name = document.firstText("h1")
            ?: document.metaContent("og:title")
                ?.substringBefore(" | Show TV")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.metaContent("og:image")?.takeIf { it.isNotBlank() }
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
        val slug = seriesUrl.substringAfter("/dizi/tanitim/", "")
            .substringBefore("/")
            .takeIf { it.isNotBlank() }
            ?: return emptyList()

        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        // The series page only shows the newest handful of episodes. Each show
        // has its own sitemap (`sitemap_<slug>.xml`) that lists every episode
        // URL, which is far cheaper than crawling the paginated archive.
        val sitemap = withTimeoutOrNull(20_000) {
            fetchText("$mainUrl/sitemap_$slug.xml")
        }
        val episodePattern = Regex("""-sezon-(\d+)-bolum-(\d+)""")
        for (loc in sitemapLocations(sitemap)) {
            if (!loc.contains("/dizi/tum_bolumler/")) continue
            val slugPart = loc.substringAfter("/dizi/tum_bolumler/", "").substringBefore("/")
            if (!slugPart.startsWith("$slug-sezon-")) continue
            val match = episodePattern.find(slugPart) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val number = match.groupValues[2].toIntOrNull() ?: continue
            val href = loc.substringBefore("?").trimEnd('/')
            if (!seen.add(href)) continue
            result.add(
                SiteEpisode(
                    name = "$number. Bölüm",
                    url = href,
                    season = season,
                    episode = number,
                )
            )
        }
        if (result.isNotEmpty()) {
            // Show TV numbers episodes continuously across the whole run (for
            // example season 5 starts at 139). Renumber each season from 1 so
            // the listing agrees with TMDB's season/episode scheme.
            return renumberSeasons(result)
        }

        // Fallback: the landing page itself lists the most recent episodes.
        for (anchor in landing.select("a[href*='/dizi/tum_bolumler/']")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank()) continue
            val slugPart = href.substringAfter("/dizi/tum_bolumler/", "").substringBefore("/")
            if (slugPart.isBlank()) continue
            if (!slugPart.startsWith("$slug-sezon-") &&
                !slugPart.startsWith("$slug-bolum-") &&
                !slugPart.startsWith("$slug-")
            ) {
                continue
            }
            if (!seen.add(href)) continue

            val seasonWith = Regex("""-sezon-(\d+)-bolum-(\d+)""").find(slugPart)
            val season = seasonWith?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val number = seasonWith?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: Regex("""-bolum-(\d+)""").find(slugPart)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                .ifBlank { "S$season B$number" }

            result.add(
                SiteEpisode(
                    name = label,
                    url = href,
                    season = season,
                    episode = number,
                )
            )
        }
        return result.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
    }

    /** Shifts each season's cumulative episode numbers to start at 1. */
    private fun renumberSeasons(items: List<SiteEpisode>): List<SiteEpisode> {
        val result = mutableListOf<SiteEpisode>()
        for ((season, group) in items.groupBy { it.season ?: 1 }) {
            val offset = (group.minOfOrNull { it.episode ?: 1 } ?: 1) - 1
            for (item in group) {
                result.add(
                    item.copy(
                        season = season,
                        episode = ((item.episode ?: 1) - offset).coerceAtLeast(1),
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
        val html = document.html()
        val links = mutableListOf<Pair<String, Boolean>>()

        for (stream in jsonLdContentUrls(document)) {
            if (stream.contains(".mp4") || stream.contains(".m3u8")) {
                links.add(stream to stream.contains(".m3u8"))
            }
        }

        // The player config is HTML-escaped inside a data attribute. Unescaping
        // exposes the adaptive manifest (`media.m3u8[0].src`).
        val unescaped = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(unescaped)?.value
            ?.let { links.add(it to true) }
        Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(unescaped)?.value
            ?.let { links.add(it to false) }

        val unique = links
            .filter { it.first.isNotBlank() }
            .distinctBy { it.first }
        if (unique.isEmpty()) return false

        for ((stream, isHls) in unique) {
            emitLink(
                source = name,
                label = if (isHls) "HLS" else qualityLabel(stream),
                url = stream,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = url,
                callback = callback,
            )
        }
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
        const val ARCHIVE_KEY = "showtv:archive"
        const val PAGE_SIZE = 60
    }
}

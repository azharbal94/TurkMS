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
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.titleScore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * Kanal D (kanald.com.tr).
 *
 * The current series live on `/diziler`; the archive is paginated on
 * `/diziler/arsiv?page=N`. Each series has a `/bolumler` tab that renders every
 * season (and its episodes) as normal anchors. Playback is a direct HLS URL in
 * the episode's JSON-LD `VideoObject.contentUrl`.
 */
class KanalDSource : SiteSource() {
    override val key = "kanald"
    override val name = "Kanal D"
    override val mainUrl = "https://www.kanald.com.tr"
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
            // Fetch the current grid and every archive page at once; doing this
            // sequentially easily exceeds the aggregator's search timeout.
            val documents = coroutineScope {
                buildList {
                    add(async { withTimeoutOrNull(25_000) { fetchDocument("$mainUrl/diziler") } })
                    for (page in 1..ARCHIVE_PAGES) {
                        add(
                            async {
                                withTimeoutOrNull(20_000) {
                                    fetchDocument("$mainUrl/diziler/arsiv?page=$page")
                                }
                            }
                        )
                    }
                }.awaitAll()
            }

            val results = mutableListOf<SiteResult>()
            for (document in documents) {
                if (document != null) parsePosterCards(document, results)
            }

            val items = results.distinctBy { it.url }
            if (items.isEmpty()) null else items
        }.orEmpty()
    }

    private fun parsePosterCards(document: Document, target: MutableList<SiteResult>) {
        for (anchor in document.select("a.poster-card[href]")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank()) continue
            if (!href.contains("kanald.com.tr")) continue
            val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().trimEnd('/')
            if (path.count { it == '/' } != 1) continue
            if (path.startsWith("/diziler")) continue
            val title = anchor.selectFirst("img")?.attr("alt")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: anchor.text().replace(Regex("\\s+"), " ").trim()
                    .takeIf { it.isNotBlank() && !it.equals("Takip Et", true) }
                ?: titleFromSlug(href)
            if (title.isBlank()) continue

            val poster = anchor.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }
            target.add(SiteResult(title, href, TvType.TvSeries, poster = poster))
        }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val name = document.firstText("h1")
            ?: document.metaContent("og:title")
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.metaContent("og:image")?.takeIf { it.isNotBlank() }
        val plot = document.metaContent("og:description", "description")
        val episodes = parseEpisodes(url)

        return SiteDetails(
            name = name,
            url = url,
            type = TvType.TvSeries,
            poster = poster,
            plot = plot,
            episodes = episodes,
        )
    }

    private suspend fun parseEpisodes(seriesUrl: String): List<SiteEpisode> {
        val path = runCatching { java.net.URI(seriesUrl).path }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val first = withTimeoutOrNull(25_000) {
            fetchDocument("$mainUrl$path/bolumler", referer = seriesUrl)
        } ?: return emptyList()

        val seasonCount = Regex("""(?:&amp;|&)?seasonCount=(\d+)""")
            .find(first.html())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.coerceIn(1, MAX_SEASONS) ?: 1

        data class Found(val href: String, val number: Int, val label: String)

        // Episode numbers are cumulative across the whole series and each
        // season page is paginated (30 per page, ascending order). Fetch every
        // season and page in parallel, then renumber each season from 1 so the
        // listing lines up with TMDB.
        val perSeason = coroutineScope {
            (1..seasonCount).map { season ->
                async {
                    val found = mutableListOf<Found>()
                    val localSeen = mutableSetOf<String>()
                    for (page in 1..MAX_EPISODE_PAGES) {
                        val url = "$mainUrl$path/bolumler" +
                            "?activeSeasonNumber=$season&seasonCount=$seasonCount" +
                            "&page=$page&orderby=EpisodeNumber%20asc"
                        val document = withTimeoutOrNull(15_000) {
                            fetchDocument(url, referer = seriesUrl)
                        } ?: break
                        var added = false
                        for (anchor in document.select("a[href*='/bolumler/']")) {
                            val href = absoluteUrl(mainUrl, anchor.attr("href"))
                            if (href.isBlank() || href.contains("son-bolum")) continue
                            val match = Regex("""/bolumler/[^/?#]+-(\d+)-bolum/?$""")
                                .find(href) ?: continue
                            val number = match.groupValues[1].toIntOrNull() ?: continue
                            if (!localSeen.add(href)) continue
                            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                                .ifBlank { "$number. Bölüm" }
                            found.add(Found(href, number, label))
                            added = true
                        }
                        if (!added) break
                    }
                    season to found
                }
            }.awaitAll()
        }

        val result = mutableListOf<SiteEpisode>()
        for ((season, found) in perSeason) {
            if (found.isEmpty()) continue
            val offset = (found.minOf { it.number } - 1).coerceAtLeast(0)
            for (item in found) {
                result.add(
                    SiteEpisode(
                        name = item.label,
                        url = item.href,
                        season = season,
                        episode = (item.number - offset).coerceAtLeast(1),
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
        val stream = jsonLdContentUrls(document).firstOrNull { it.contains(".m3u8") }
            ?: Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.value
            ?: jsonLdContentUrls(document).firstOrNull { it.contains(".mp4") }
            ?: Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(html)?.value
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
        const val ARCHIVE_KEY = "kanald:archive"
        const val PAGE_SIZE = 60
        const val ARCHIVE_PAGES = 5
        const val MAX_SEASONS = 15
        const val MAX_EPISODE_PAGES = 20
    }
}

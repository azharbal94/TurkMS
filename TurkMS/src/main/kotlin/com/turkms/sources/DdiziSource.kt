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
import com.turkms.extractFirePlayer
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.normalizeTitle
import com.turkms.parseEpisodeNumber
import com.turkms.parseJwSources
import com.turkms.parseSeasonNumber
import com.turkms.postDocument
import kotlinx.coroutines.withTimeoutOrNull

class DdiziSource : SiteSource() {
    override val key = "ddizi"
    override val name = "Ddizi"
    override val mainUrl = "https://www.ddizi.tel"
    override val supportsMovies = true

    override suspend fun search(query: String): List<SiteResult> {
        val document = postDocument(
            "$mainUrl/arama/",
            data = mapOf("arama" to query),
            referer = "$mainUrl/",
        ) ?: return emptyList()

        val results = mutableListOf<SiteResult>()
        for (box in document.select("div.dizi-boxpost-cat")) {
            val link = box.selectFirst("a[href]") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = link.attr("title").ifBlank { link.text() }
                .replace(Regex("(?i)\\s+son\\s+b[oö]l[uü]m\\s+izle.*$"), "")
                .replace(Regex("(?i)\\s+izle.*$"), "")
                .trim()
            if (title.isBlank()) continue
            val type = if (href.contains("/diziler/")) TvType.TvSeries else TvType.Movie
            val poster = box.selectFirst("img")?.firstImage()?.let { absoluteUrl(mainUrl, it) }
            results.add(SiteResult(title, href, type, null, poster))
        }
        return results
            .distinctBy { it.url }
            .sortedByDescending { if (it.type == TvType.TvSeries) 1 else 0 }
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        // Ddizi's "new" page has only ~30 cards, but /eski.diziler contains
        // the complete Turkish series archive. The native provider pages
        // through that combined list locally so no title is hidden.
        val catalog = loadCatalog()
        if (catalog.isEmpty()) return emptyList()
        val pageSize = 90
        return catalog.drop((page - 1) * pageSize).take(pageSize)
    }

    @Volatile
    private var catalogCache: Pair<Long, List<SiteResult>>? = null

    private suspend fun loadCatalog(): List<SiteResult> {
        val now = System.currentTimeMillis()
        catalogCache?.let { (time, items) ->
            if (now - time < CATALOG_TTL_MS) return items
        }

        val latest = withTimeoutOrNull(15_000) { fetchDocument("$mainUrl/yeni-eklenenler8") }
        val archive = withTimeoutOrNull(15_000) { fetchDocument("$mainUrl/eski.diziler") }
        if (latest == null && archive == null) return emptyList()

        val foreignUrls = withTimeoutOrNull(10_000) {
            fetchDocument("$mainUrl/yabanci-dizi-izle")
                ?.select("div.dizi-boxpost a[href*=/diziler/], div.dizi-boxpost-cat a[href*=/diziler/]")
                ?.mapNotNull { link ->
                    absoluteUrl(mainUrl, link.attr("href")).takeIf { it.isNotBlank() }
                }
                ?.toSet()
                .orEmpty()
        }.orEmpty()

        val items = mutableListOf<SiteResult>()
        latest?.let { items.addAll(parseLinks(it, foreignUrls)) }
        archive?.let { items.addAll(parseLinks(it, foreignUrls)) }
        val deduped = items.distinctBy { it.url }
            .distinctBy { normalizeTitle(it.name) }
        if (deduped.isNotEmpty()) catalogCache = now to deduped
        return deduped
    }

    private fun parseLinks(
        document: org.jsoup.nodes.Document,
        foreignUrls: Set<String>,
    ): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        // Card links are parsed before sidebar links so the "Latest" row starts
        // with actual new episodes; the sidebar on /eski.diziler is the only
        // complete Turkish-series index the site exposes.
        val links = document.select(
            "div.dizi-boxpost-cat a[href*=/diziler/], div.dizi-boxpost a[href*=/diziler/]"
        ) + document.select("a[href*=/diziler/]")
        for (link in links) {
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            if (href in foreignUrls) continue
            if (KNOWN_FOREIGN_SLUGS.any { slug -> href.contains(slug) }) continue

            val title = (link.attr("title").ifBlank { link.text() })
                .replace(Regex("(?i)\\s+son\\s+b[oö]l[uü]m\\s+izle.*$"), "")
                .replace(Regex("(?i)\\s+izle.*$"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (title.isBlank()) continue
            val container = link.closest("div.dizi-boxpost-cat, div.dizi-boxpost, li")
            val poster = (container?.selectFirst("img") ?: link.selectFirst("img"))
                ?.firstImage()
                ?.let { absoluteUrl(mainUrl, it) }
            results.add(SiteResult(title, href, TvType.TvSeries, null, poster))
        }
        return results.distinctBy { it.url }
    }

    private companion object {
        const val CATALOG_TTL_MS = 10 * 60 * 1000L

        // Fallback if the foreign-list page is temporarily unavailable. These
        // are the non-Turkish series currently present on the latest page.
        val KNOWN_FOREIGN_SLUGS = setOf(
            "1899",
            "berlin",
            "bridgerton-hd",
            "dark-hd",
            "elite-hd",
            "la-casa-de-papel-hd",
            "narcos-hd",
            "ozark-hd",
            "peaky-blinders-hd27",
            "rise-of-empires-ottoman-hd",
            "riverdale-hd",
            "snowpiercer-hd04",
            "squid-game-hd23",
            "stranger-things-hd15",
            "the-100",
            "the-last-dance-hd",
            "the-witcher-hd",
            "wednesday-hd",
        )
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val isSeries = url.contains("/diziler/")
        val metaTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.title()
        val title = metaTitle
            .replace(Regex("(?i)\\s*\\|\\s*Ddizi.*$"), "")
            .replace(Regex("(?i)\\s+son\\s+b[oö]l[uü]m\\s+izle.*$"), "")
            .replace(Regex("(?i)\\s+izle\\s+full.*$"), "")
            .replace(Regex("(?i)\\s+izle.*$"), "")
            .trim()

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val episodes = if (isSeries) parseAllEpisodes(url, document) else emptyList()
        val year = Regex("(19|20)\\d{2}")
            .find(document.selectFirst("div.title_con")?.text() ?: "")
            ?.value?.toIntOrNull()

        return SiteDetails(
            name = title.ifBlank { "Ddizi" },
            url = url,
            type = if (isSeries) TvType.TvSeries else TvType.Movie,
            poster = poster,
            plot = plot,
            year = year,
            episodes = episodes,
        )
    }

    /**
     * The detail page only lists the newest 10 episodes; the rest live behind
     * `/sayfa-N` links. Follow them so the episode list is complete.
     */
    private suspend fun parseAllEpisodes(
        firstUrl: String,
        first: org.jsoup.nodes.Document,
    ): List<SiteEpisode> {
        val episodes = parseEpisodes(first).toMutableList()
        val seen = episodes.mapTo(mutableSetOf()) { it.url }

        val pages = first.select("div.content_ a[href*=/sayfa-]")
            .map { absoluteUrl(mainUrl, it.attr("href")) }
            .filter { it.isNotBlank() && it != firstUrl }
            .distinct()
            // Long-running shows (e.g. Kızılcık Şerbeti) expose 15+ pages.
            // Follow all of them; the site's own pages are 10 episodes each.
            .take(30)

        for (pageUrl in pages) {
            val page = fetchDocument(pageUrl, firstUrl) ?: continue
            for (episode in parseEpisodes(page)) {
                if (seen.add(episode.url)) episodes.add(episode)
            }
        }
        return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<SiteEpisode> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        // Scope to the content column, otherwise the "Bugünkü Bölümler" sidebar
        // of other series leaks into this show's episode list.
        for (link in document.select(
            "div.content_ div.dizi-boxpost-cat a[href*=izle], div.content_ div.video-g a[href*=izle]"
        )) {
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank() || !seen.add(href)) continue
            val label = link.attr("title").ifBlank { link.text() }
                .replace(Regex("(?i)\\s+izle.*$"), "")
                .trim()
            if (label.isBlank()) continue
            result.add(
                SiteEpisode(
                    name = label,
                    url = href,
                    season = parseSeasonNumber(label) ?: 1,
                    episode = parseEpisodeNumber(label),
                )
            )
        }
        return result
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        val iframe = findPlayerIframe(document) ?: return false
        val playerUrl = absoluteUrl(mainUrl, iframe)
        if (playerUrl.contains("/fireplayer/video/")) {
            if (extractFirePlayer(playerUrl, url, name, callback)) return true
        }
        if (playerUrl.contains("/player/oynat/")) {
            val html = fetchText(playerUrl, url) ?: return false
            val sources = parseJwSources(html)
            if (sources.isNotEmpty()) {
                for ((rawFile, label, type) in sources) {
                    val file = cleanPlayerUrl(rawFile)
                    val isTwitter = file.contains("video.twimg.com")
                    emitLink(
                        source = name,
                        label = label,
                        url = absoluteUrl(playerUrl, file),
                        type = if (type.contains("hls") || file.contains(".m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        },
                        // video.twimg.com returns 403 when the Ddizi page is
                        // used as Referer. x.com (or no Referer) works.
                        referer = if (isTwitter) "https://x.com/" else playerUrl,
                        callback = callback,
                    )
                }
                return true
            }
        }
        return extractWithBuiltins(playerUrl, url, callback)
    }
}

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
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.parseEpisodeNumber
import com.turkms.parseSeasonNumber
import com.turkms.postText
import com.turkms.titleScore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * NOW (nowtv.com.tr) — the former Fox Turkey on-demand portal.
 *
 * The series archive is exposed as a sitemap (`sitemap_series.xml`) and as the
 * `/dizi-arsivi` cards. Every series page has a `/bolumler` tab whose embedded
 * Vue payload lists the full episodes (`video_type_id: 2`). The episode page
 * itself only carries the player configuration; playback is a POST to
 * `/ajax/stream` with the numeric video id, which answers with an HLS URL.
 */
class NowTvSource : SiteSource() {
    override val key = "nowtv"
    override val name = "NOW TV"
    override val mainUrl = "https://www.nowtv.com.tr"
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
            val names = mutableMapOf<String, String>()
            withTimeoutOrNull(25_000) { fetchDocument("$mainUrl/dizi-arsivi") }?.let { document ->
                for (poster in document.select("div.poster")) {
                    val link = poster.selectFirst("a[href*='/izle']") ?: continue
                    val href = absoluteUrl(mainUrl, link.attr("href"))
                    if (href.isBlank()) continue
                    val title = poster.selectFirst("img")?.attr("alt")?.trim().orEmpty()
                    if (title.isNotBlank()) names[href] = title
                }
            }

            val xml = withTimeoutOrNull(30_000) { fetchText("$mainUrl/sitemap_series.xml") }
            val urls = sitemapLocations(xml)
                .filter { it.matches(Regex(""".*/[^/]+/izle/?$""")) }
                .distinct()

            val items = urls.mapNotNull { url ->
                val clean = url.trimEnd('/')
                val title = names[clean].orEmpty().ifBlank { titleFromNowUrl(clean) }
                if (title.isBlank()) null else SiteResult(title, clean, TvType.TvSeries)
            }.distinctBy { it.url }

            if (items.isEmpty()) null else items
        }.orEmpty()
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val name = document.firstText("h1")
            ?: document.metaContent("og:title")
                ?.substringAfter("Dizi İzle - ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromNowUrl(url).takeIf { it.isNotBlank() }
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
        val path = runCatching { java.net.URI(seriesUrl).path }.getOrNull()
            ?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val listPage = withTimeoutOrNull(25_000) {
            fetchDocument("$mainUrl$path/bolumler", referer = seriesUrl)
        }
        val html = listPage?.html() ?: landing.html()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        // The page embeds the active season's episodes as a JSON array:
        // {"id":137490,"name":"41. Bölüm","program_id":1808,"video_type_id":2,...,"bolum_no":41}
        val programId = Regex("""programId\s*:\s*['"]?(\d+)""")
            .find(html)?.groupValues?.getOrNull(1)
        val episodes = extractJsonArray(html, "episodes")
        if (episodes != null) {
            for (index in 0 until episodes.length()) {
                val item = episodes.optJSONObject(index) ?: continue
                if (item.optInt("video_type_id", -1) != 2) continue
                val itemProgram = item.optInt("program_id", -1)
                if (programId != null && itemProgram > 0 && itemProgram.toString() != programId) {
                    continue
                }
                val number = item.optInt("bolum_no", -1)
                if (number <= 0) continue
                val episodeUrl = "$mainUrl$path/bolum/$number"
                if (!seen.add(episodeUrl)) continue
                val season = item.optInt("season", 1).takeIf { it > 0 } ?: 1
                val label = item.optString("name").trim().ifBlank { "$number. Bölüm" }
                result.add(
                    SiteEpisode(
                        name = label,
                        url = episodeUrl,
                        season = season,
                        episode = number,
                    )
                )
            }
        }

        // The newest episode is occasionally stamped with an older season than
        // the episode just before it (site data glitch). Keep the run monotonic
        // so the newest episode is not filed under an earlier season.
        if (result.size > 1) {
            val ordered = result.sortedBy { it.episode ?: 0 }.toMutableList()
            var runningSeason = ordered.first().season ?: 1
            for (index in ordered.indices) {
                val item = ordered[index]
                val season = item.season ?: 1
                if (season >= runningSeason) {
                    runningSeason = season
                } else if (season != runningSeason) {
                    ordered[index] = item.copy(season = runningSeason)
                }
            }
            result.clear()
            result.addAll(ordered)
        }

        // Fallback for pages that render plain anchors instead of the Vue payload.
        if (result.isEmpty()) {
            for (page in listOfNotNull(landing, listPage)) {
                for (anchor in page.select("a[href*='/bolum/']")) {
                    val href = absoluteUrl(mainUrl, anchor.attr("href"))
                    val number = Regex("""/bolum/(\d+)/?$""").find(href)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: parseEpisodeNumber(href)
                        ?: continue
                    if (href.isBlank() || !seen.add(href)) continue
                    val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                        .ifBlank { "$number. Bölüm" }
                    result.add(
                        SiteEpisode(
                            name = label,
                            url = href,
                            season = parseSeasonNumber(label) ?: 1,
                            episode = number,
                        )
                    )
                }
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
        val videoId = Regex("""video_id\s*:\s*(\d+)""").find(html)?.groupValues?.getOrNull(1)
            ?: document.selectFirst("[data-video-id]")?.attr("data-video-id")?.takeIf { it.isNotBlank() }
            ?: return false

        val body = postText(
            url = "$mainUrl/ajax/stream",
            data = mapOf("video_id" to videoId),
            referer = url,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
            ),
        ) ?: return false

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (json.optInt("code", 200) != 200) return false
        val stream = cleanPlayerUrl(json.optString("video_url")).trim()
        if (stream.isBlank()) return false

        emitLink(
            source = name,
            label = "HLS",
            url = stream,
            type = ExtractorLinkType.M3U8,
            referer = url,
            callback = callback,
        )
        return true
    }

    private companion object {
        const val ARCHIVE_KEY = "nowtv:archive"
        const val PAGE_SIZE = 60
    }

    /** `/Halef-Koklerin-Cagrisi/izle` -> `Halef Koklerin Cagrisi`. */
    private fun titleFromNowUrl(url: String): String {
        val clean = url.substringBefore("?").trimEnd('/')
        val slug = if (clean.endsWith("/izle")) {
            clean.substringBeforeLast('/').substringAfterLast('/')
        } else {
            clean.substringAfterLast('/')
        }
        return titleFromSlug(slug)
    }
}

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
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.titleScore
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Star TV (startv.com.tr).
 *
 * `/dizi` lists the running shows; each series page and its `/bolumler` tab
 * link episodes as `/dizi/<slug>/bolumler/<n>-bolum`. The episode page is a
 * Next.js document whose RSC payload carries `"videoId"` and `"referenceId"`.
 * The DYG player API then returns the adaptive manifest in `data.flavors.hls`.
 */
class StartvSource : SiteSource() {
    override val key = "startv"
    override val name = "Star TV"
    override val mainUrl = "https://www.startv.com.tr"
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
            val document = withTimeoutOrNull(25_000) { fetchDocument("$mainUrl/dizi") }
                ?: return@getOrPut null
            val results = mutableListOf<SiteResult>()
            for (anchor in document.select("a[href^='/dizi/']")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                if (href.isBlank()) continue
                val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().trimEnd('/')
                if (path.count { it == '/' } != 2) continue
                if (!path.startsWith("/dizi/")) continue

                val img = anchor.selectFirst("img[alt]")
                val title = img?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                    ?: anchor.text().replace(Regex("\\s+"), " ").trim()
                        .takeIf { it.isNotBlank() && it.any { ch -> ch.isLetter() } }
                    ?: titleFromSlug(href)
                if (title.isBlank()) continue

                val poster = img?.firstImage()?.takeIf { it.isNotBlank() }
                    ?.let { absoluteUrl(mainUrl, it) }
                results.add(SiteResult(title, href, TvType.TvSeries, poster = poster))
            }
            results.distinctBy { it.url }.filter { it.name.isNotBlank() }
                .ifEmpty { null }
        }.orEmpty()
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val name = document.firstText("h1")
            ?: document.metaContent("og:title")
                ?.removeSuffix(" | Star TV")
                ?.removeSuffix(" İzle")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.metaContent("og:image")?.takeIf { !it.contains("/images/d(") }
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
            fetchDocument("$seriesUrl/bolumler", referer = seriesUrl)
        }

        val pattern = Regex("""$path/bolumler/(\d+)-bolum/?$""")
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (page in listOfNotNull(landing, listPage)) {
            for (anchor in page.select("a[href*='/bolumler/']")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                if (href.isBlank()) continue
                val match = pattern.find(href) ?: continue
                val number = match.groupValues[1].toIntOrNull()
                    ?: parseEpisodeNumber(href)
                    ?: continue
                if (!seen.add(href)) continue
                val label = anchor.text().replace(Regex("\\s+"), " ").trim()
                    .ifBlank { "$number. Bölüm" }
                result.add(
                    SiteEpisode(
                        name = label,
                        url = href,
                        season = 1,
                        episode = number,
                    )
                )
            }
        }
        return result.sortedBy { it.episode ?: 0 }
    }

    override suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val document = fetchDocument(url) ?: return false
        // The RSC payload escapes the JSON quotes, so normalize before matching
        // the first videoId/referenceId pair (the current episode).
        val normalized = document.html()
            .replace("\\\"", "\"")
            .replace("\\/", "/")
        val pair = Regex(
            """"videoId"\s*:\s*"(\d+)"\s*,\s*"referenceId"\s*:\s*"([A-Za-z0-9_-]+)""""
        ).find(normalized) ?: return false
        val referenceId = pair.groupValues[2]
        if (referenceId.isBlank()) return false

        val api = "https://dygvideo.dygdigital.com/api/video_info" +
            "?akamai=true&PublisherId=1" +
            "&ReferenceId=StarTv_${referenceId}" +
            "&SecretKey=NtvApiSecret2014%2A"
        val body = fetchText(api, referer = url) ?: return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        val flavors = json.optJSONObject("data")?.optJSONObject("flavors") ?: return false

        val hls = cleanPlayerUrl(flavors.optString("hls")).trim()
            .takeIf { it.startsWith("http") }
            ?: cleanPlayerUrl(flavors.optString("0")).trim().takeIf { it.startsWith("http") }
            ?: return false

        emitLink(
            source = name,
            label = "HLS",
            url = hls,
            type = ExtractorLinkType.M3U8,
            referer = url,
            callback = callback,
        )
        return true
    }

    private companion object {
        const val ARCHIVE_KEY = "startv:archive"
        const val PAGE_SIZE = 60
    }
}

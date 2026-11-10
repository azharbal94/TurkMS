package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.TurkMSCache
import com.turkms.absoluteUrl
import com.turkms.extractYoutubeWithFallback
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.titleScore
import com.turkms.youtubeId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.nodes.Document

/**
 * TRT 1 (trt1.com.tr).
 *
 * `/diziler?archive=false` lists the running shows and `?archive=true` the
 * archive. Every series page links its episodes as
 * `/diziler/<slug>/bolum/<n>-bolum`, and each episode's JSON-LD
 * `VideoObject.contentUrl` is the official TRT YouTube upload. Playback is
 * delegated to the existing YouTube extractor, which keeps TRT's 1080p
 * adaptive manifest.
 */
class Trt1Source : SiteSource() {
    override val key = "trt1"
    override val name = "TRT 1"
    override val mainUrl = "https://www.trt1.com.tr"
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
            // Running and archived shows live on two variants of the same page.
            val documents = coroutineScope {
                listOf("false", "true").map { archive ->
                    async {
                        withTimeoutOrNull(25_000) {
                            fetchDocument("$mainUrl/diziler?archive=$archive")
                        }
                    }
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
        for (anchor in document.select("a[href^='/diziler/']")) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank()) continue
            val path = runCatching { java.net.URI(href).path }.getOrNull().orEmpty().trimEnd('/')
            // Only the series landing page: /diziler/<slug>, never /bolum/...
            if (path.count { it == '/' } != 2) continue
            if (!path.startsWith("/diziler/")) continue

            val img = anchor.selectFirst("img[alt]")
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
        val name = document.firstText("h1")
            ?: document.metaContent("og:title")
                ?.substringBefore(" - TRT")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: titleFromSlug(url).takeIf { it.isNotBlank() }
            ?: return null

        val poster = jsonLdImage(document)
            ?: document.metaContent("og:image")?.takeIf { !it.contains("/logo/") }
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

        // The landing page already lists recent episodes; the "Tüm Bölümler"
        // tab is a separate document that carries the complete list.
        val listPage = withTimeoutOrNull(20_000) {
            fetchDocument("$seriesUrl/bolum", referer = seriesUrl)
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()

        // `sitemap_content.xml` lists every episode URL; filter it by this
        // series' path so the full run is available without pagination.
        val sitemap = withTimeoutOrNull(20_000) {
            fetchText("$mainUrl/sitemap_content.xml")
        }
        val pattern = Regex("""$path/bolum/(\d+)-bolum/?$""")
        for (loc in sitemapLocations(sitemap)) {
            val href = loc.substringBefore("?").trimEnd('/')
            val match = pattern.find(href) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (!seen.add(href)) continue
            result.add(
                SiteEpisode(
                    name = "$number. Bölüm",
                    url = href,
                    season = 1,
                    episode = number,
                )
            )
        }
        if (result.isNotEmpty()) return result.sortedBy { it.episode ?: 0 }

        for (page in listOfNotNull(landing, listPage)) {
            for (anchor in page.select("a[href*='/bolum/']")) {
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
        val candidates = jsonLdContentUrls(document) +
            Regex("""https?://[^"'\s]*(?:youtube\.com|youtu\.be)/[^"'\s]+""")
                .findAll(document.html())
                .map { it.value }
                .toList()

        for (candidate in candidates) {
            val id = youtubeId(candidate) ?: continue
            if (extractYoutubeWithFallback(id, url, callback)) return true
        }
        return false
    }

    private companion object {
        const val ARCHIVE_KEY = "trt1:archive"
        const val PAGE_SIZE = 60
    }
}

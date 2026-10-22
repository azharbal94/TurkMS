package com.turkms.sources

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.turkms.SiteDetails
import com.turkms.SiteEpisode
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.absoluteUrl
import com.turkms.emitLink
import com.turkms.encode
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.parseJwSources
import com.turkms.parseSeasonNumber
import com.turkms.parseYear

class TrDiziIzleSource : SiteSource() {
    override val key = "trdiziizle"
    override val name = "Trdiziizle"
    override val mainUrl = "https://www.trdiziizle.tv"

    override suspend fun search(query: String): List<SiteResult> {
        val document = fetchDocument("$mainUrl/?s=${encode(query)}") ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page > 1) return emptyList()
        // Same WordPress theme as canlidizi14; fall back to the home page if the
        // series section is not available.
        val series = fetchDocument("$mainUrl/kategori/e/")
        val fromSeries = series?.let { parseCards(it) }.orEmpty()
        if (fromSeries.isNotEmpty()) return fromSeries
        val home = fetchDocument("$mainUrl/") ?: return emptyList()
        return parseCards(home)
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (item in document.select("div.single-item")) {
            val link = item.selectFirst("div.categorytitle a, div.cat-title a") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = link.text().ifBlank {
                item.selectFirst("div.cat-img img")?.attr("alt") ?: ""
            }
            if (title.isBlank()) continue
            val poster = item.selectFirst("div.cat-img img")?.firstImage()
                ?.let { absoluteUrl(mainUrl, it) }
            results.add(
                SiteResult(
                    name = title,
                    url = href,
                    type = if (href.contains("/diziler/")) TvType.TvSeries else TvType.Movie,
                    year = parseYear(item.text()),
                    poster = poster,
                )
            )
        }
        return results.distinctBy { it.url }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val episodes = parseEpisodes(document)
        val isSeries = url.contains("/diziler/") || episodes.isNotEmpty()

        val title = document.selectFirst("h1")?.text()
            ?.replace(Regex("(?i)\\s+son\\s+b[oö]l[uü]m\\s+izle.*$"), "")
            ?.replace(Regex("(?i)\\s+izle\\s*$"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore("|").trim()

        val poster = document.selectFirst("div.category_image img")?.firstImage()
            ?.let { absoluteUrl(mainUrl, it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")

        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        for (span in document.select("#icerikcat2 span.dizimeta")) {
            val label = span.text()
            val value = span.nextSibling()?.toString()?.trim()
                ?.substringBefore("<br")?.trim()
            if (value.isNullOrBlank()) continue
            when {
                label.startsWith("Oyuncular", true) -> value.split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }.forEach { actors.add(it) }
                label.startsWith("Tür", true) -> value.split(",")
                    .map { it.trim() }.filter { it.isNotBlank() }.forEach { tags.add(it) }
            }
        }
        val metaText = document.selectFirst("#icerikcat2")?.text() ?: ""

        return SiteDetails(
            name = title,
            url = url,
            type = if (isSeries) TvType.TvSeries else TvType.Movie,
            poster = poster,
            plot = plot,
            year = parseYear(metaText),
            tags = tags,
            actors = actors,
            episodes = episodes,
        )
    }

    private fun parseEpisodes(document: org.jsoup.nodes.Document): List<SiteEpisode> {
        val anchors = document.select(
            "#scrollbar-container div.bolumust a[href], div.container div.bolumust a[href]"
        )
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (anchor in anchors) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank() || !seen.add(href)) continue
            val label = anchor.selectFirst("div.baslik")?.text()
                ?: anchor.attr("title").ifBlank { anchor.text() }
            val name = label.replace(Regex("\\s+"), " ").trim()
            if (name.isBlank()) continue
            result.add(
                SiteEpisode(
                    name = name,
                    url = href,
                    season = parseSeasonNumber(name) ?: 1,
                    episode = parseEpisodeNumber(name),
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
        if (playerUrl.contains("/player/oynat/")) {
            val html = fetchText(playerUrl, url) ?: return false
            val sources = parseJwSources(html)
            if (sources.isNotEmpty()) {
                for ((file, label, type) in sources) {
                    emitLink(
                        source = name,
                        label = label,
                        url = absoluteUrl(playerUrl, file),
                        type = if (type.contains("hls") || file.contains(".m3u8")) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        },
                        referer = playerUrl,
                        callback = callback,
                    )
                }
                return true
            }
        }
        return extractWithBuiltins(playerUrl, url, callback)
    }
}

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
import com.turkms.firstImage
import com.turkms.findPlayerIframe
import com.turkms.parseEpisodeNumber
import com.turkms.parseSeasonNumber
import com.turkms.parseYear
import com.turkms.postText
import com.turkms.youtubeId
import org.json.JSONObject
import org.jsoup.nodes.Document

class CanliDizi14Source : SiteSource() {
    override val key = "canlidizi14"
    override val name = "CanlıDizi14"
    override val mainUrl = "https://www.canlidizi14.net"
    override val supportsMovies = true

    override suspend fun search(query: String): List<SiteResult> {
        val document = fetchDocument("$mainUrl/?s=${encode(query)}") ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page > 1) return emptyList()
        val document = fetchDocument("$mainUrl/kategori/e/") ?: return emptyList()
        return parseCards(document)
    }

    private fun parseCards(document: Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (item in document.select("div.single-item")) {
            val link = item.selectFirst("div.categorytitle a, div.cat-title a") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = link.text().ifBlank {
                item.selectFirst("div.cat-img img")?.attr("alt") ?: ""
            }
            if (title.isBlank()) continue
            // The whole site lives under /kategori/; series use the /kategori/e/
            // section, films (and digital series) live under other sub-sections.
            val type = if (href.contains("/kategori/e/")) TvType.TvSeries else TvType.Movie
            val year = parseYear(item.text())
            val poster = item.selectFirst("div.cat-img img")?.firstImage()
                ?.let { absoluteUrl(mainUrl, it) }
            results.add(SiteResult(title, href, type, year, poster))
        }
        return results.distinctBy { it.url }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val content = document.selectFirst("div.cat-container-in, div.cat-container")
        val metaText = content?.text() ?: document.body().text()
        val episodes = parseEpisodes(document)
        // Every URL contains /kategori/, so only the episode list tells us what
        // this page is. A page without episodes is treated as a film.
        val isSeries = episodes.isNotEmpty()

        val title = document.selectFirst("h1")?.text()
            ?.replace(Regex("(?i)\\s+son\\s+b[oö]l[uü]m\\s+izle.*$"), "")
            ?.replace(Regex("(?i)\\s+izle\\s*$"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore("|").trim()

        val poster = document.selectFirst("div.category_image img")?.firstImage()
            ?.let { absoluteUrl(mainUrl, it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")

        val plot = document.selectFirst("div.cat_ozet")?.text()
            ?: document.selectFirst("#dizinot2")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val tags = mutableListOf<String>()
        val actors = mutableListOf<String>()
        for (row in document.select("div.cat-container-in > div, #icerikcat2 > span, #icerikcat2")) {
            parseMetaRow(row.text(), tags, actors)
        }

        val score = Regex("(?i)IMDb\\s*:?\\s*([0-9]+(?:[.,][0-9]+)?)")
            .find(metaText)?.groupValues?.getOrNull(1)
            ?.replace(',', '.')?.toDoubleOrNull()

        return SiteDetails(
            name = title,
            url = url,
            type = if (isSeries) TvType.TvSeries else TvType.Movie,
            poster = poster,
            plot = plot,
            year = parseYear(metaText),
            tags = tags,
            actors = actors,
            score = score,
            episodes = episodes,
        )
    }

    private fun parseMetaRow(text: String, tags: MutableList<String>, actors: MutableList<String>) {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        when {
            clean.startsWith("Yapım Yılı", true) -> Unit
            clean.startsWith("Oyuncular", true) -> {
                clean.substringAfter(":").split(",").map { it.trim() }
                    .filter { it.isNotBlank() }.forEach { actors.add(it) }
            }
            clean.startsWith("Tür", true) -> {
                clean.substringAfter(":").split(",").map { it.trim() }
                    .filter { it.isNotBlank() }.forEach { tags.add(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document): List<SiteEpisode> {
        val anchors = document.select(
            "#scrollbar-container div.bolumust a[href], div.container div.bolumust a[href]"
        )
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (anchor in anchors) {
            val href = absoluteUrl(mainUrl, anchor.attr("href"))
            if (href.isBlank() || !seen.add(href)) continue
            val label = anchor.selectFirst("div.baslik")?.text()
                ?.ifBlank { anchor.attr("title") }
                ?.ifBlank { anchor.text() }
                ?: anchor.text()
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
        val ytId = youtubeId(playerUrl)
        if (ytId != null) {
            return extractWithBuiltins("https://www.youtube.com/watch?v=$ytId", url, callback)
        }
        if (playerUrl.contains("hlszone.com/video/")) {
            if (loadHlsZone(playerUrl, url, callback)) return true
        }
        if (playerUrl.contains("/player/oynat/")) {
            val jw = loadJwPlayer(playerUrl, url, callback)
            if (jw) return true
        }
        return extractWithBuiltins(playerUrl, url, callback)
    }

    private suspend fun loadHlsZone(
        playerUrl: String,
        referer: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val hash = playerUrl.substringAfterLast("/")
        if (hash.isBlank()) return false
        val api = "https://hlszone.com/player/index.php?data=$hash&do=getVideo"
        val text = postText(
            api,
            data = mapOf("hash" to hash, "r" to referer),
            referer = playerUrl,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        ) ?: return false
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val stream = json.optString("securedLink")
            .ifBlank { json.optString("videoSource") }
        if (stream.isBlank()) return false
        // HLS masters are adaptive, so the real resolution is decided by the player.
        emitLink(
            source = name,
            label = if (json.optBoolean("hls") || stream.contains(".m3u8")) "Otomatik (HLS)" else "Video",
            url = stream,
            type = if (json.optBoolean("hls") || stream.contains(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            },
            referer = playerUrl,
            callback = callback,
            headers = mapOf("Referer" to "https://hlszone.com/"),
        )
        val downloads = json.optJSONArray("downloadLinks")
        if (downloads != null) {
            for (i in 0 until downloads.length()) {
                val entry = downloads.optJSONObject(i) ?: continue
                val file = entry.optString("file").ifBlank { entry.optString("url") }
                if (file.isBlank()) continue
                emitLink(
                    source = name,
                    label = entry.optString("label").ifBlank { "1080p" },
                    url = file,
                    type = ExtractorLinkType.VIDEO,
                    referer = playerUrl,
                    callback = callback,
                )
            }
        }
        return true
    }

    private suspend fun loadJwPlayer(
        playerUrl: String,
        referer: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean {
        val html = com.turkms.fetchText(playerUrl, referer) ?: return false
        val sources = com.turkms.parseJwSources(html)
        if (sources.isEmpty()) return false
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

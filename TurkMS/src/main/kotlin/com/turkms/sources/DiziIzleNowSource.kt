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
import com.turkms.extractFirePlayer
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.parseEpisodeNumber
import com.turkms.parseYear
import com.turkms.youtubeId

class DiziIzleNowSource : SiteSource() {
    override val key = "diziizlenow"
    override val name = "Diziizle"
    override val mainUrl = "https://www.diziizle.you"

    override suspend fun search(query: String): List<SiteResult> {
        val document = fetchDocument("$mainUrl/?ara=${encode(query)}") ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page > 1) return emptyList()
        val document = fetchDocument("$mainUrl/diziler/") ?: return emptyList()
        return parseCards(document)
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (item in document.select("ul.liste li.film, li.film")) {
            val link = item.selectFirst("div.ad a[href], div.resim a[href]") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank()) continue
            val title = link.attr("title").ifBlank { link.text() }
                .replace(Regex("(?i)\\s+izle.*$"), "")
                .replace(Regex("\\s*\\d+\\s*B[oö]l[uü]m.*$"), "")
                .trim()
            if (title.isBlank()) continue
            val poster = item.selectFirst("div.resim img")?.firstImage()
                ?.let { absoluteUrl(mainUrl, it) }
            val year = item.selectFirst("div.yil")?.text()?.let { parseYear(it) }
            results.add(SiteResult(title, href, TvType.TvSeries, year, poster))
        }
        return results.distinctBy { it.url }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val header = document.selectFirst("div.bl-ust")
        val name = header?.selectFirst("div.bl-dizi-ad")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()
                ?.replace(Regex("(?i)\\s*izle.*$"), "")?.trim()
            ?: return null
        val poster = header?.selectFirst("img.bl-afis")?.firstImage()
            ?.let { absoluteUrl(mainUrl, it) }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val meta = header?.selectFirst("div.bl-meta")?.text() ?: ""
        val year = parseYear(meta)
        val tags = meta.split("•").map { it.trim() }
            .filter { it.isNotBlank() && parseYear(it) == null && !it.contains("Bölüm", true) && it.length < 30 }

        val plot = document.selectFirst("#dizi-hakkinda")?.text()
            ?: document.selectFirst("div.bl-hakkinda")?.text()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val episodes = mutableListOf<SiteEpisode>()
        val seen = mutableSetOf<String>()
        for (item in document.select("ol.bl-liste li")) {
            if (item.hasClass("bl-fragman")) continue
            val link = item.selectFirst("a[href]") ?: continue
            val href = absoluteUrl(mainUrl, link.attr("href"))
            if (href.isBlank() || !seen.add(href)) continue
            val label = item.selectFirst("span.bl-ad")?.text()
                ?: link.attr("title").ifBlank { link.text() }
            val clean = label.replace(Regex("\\s+"), " ").trim()
            if (clean.isBlank()) continue
            episodes.add(
                SiteEpisode(
                    name = clean,
                    url = href,
                    season = 1,
                    episode = parseEpisodeNumber(clean),
                    date = item.selectFirst("span.bl-tarih")?.text(),
                    poster = poster,
                )
            )
        }

        return SiteDetails(
            name = name,
            url = url,
            type = TvType.TvSeries,
            poster = poster,
            plot = plot,
            year = year,
            tags = tags,
            episodes = episodes,
        )
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
        val html = fetchText(playerUrl, url) ?: return false

        val youTube = Regex("""var\s+YT_SRC\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
        if (!youTube.isNullOrBlank()) {
            val id = youtubeId(youTube)
            if (id != null) {
                return extractWithBuiltins("https://www.youtube.com/watch?v=$id", url, callback)
            }
        }

        val videoSource = Regex("""var\s+VD_SRC\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
        if (!videoSource.isNullOrBlank()) {
            val isHls = html.contains("VD_HLS = true") || videoSource.contains(".m3u8")
            emitLink(
                source = name,
                label = if (isHls) "Otomatik (HLS)" else "Video",
                url = absoluteUrl(playerUrl, videoSource),
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                referer = url,
                callback = callback,
            )
            return true
        }

        return extractWithBuiltins(playerUrl, url, callback)
    }
}

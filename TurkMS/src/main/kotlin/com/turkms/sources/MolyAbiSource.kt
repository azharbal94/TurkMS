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
import com.turkms.encode
import com.turkms.extractWithBuiltins
import com.turkms.fetchDocument
import com.turkms.fetchText
import com.turkms.findPlayerIframe
import com.turkms.firstImage
import com.turkms.parseYear
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Shared engine for DiziMoly and DiziAbi.
 *
 * Both sites run the same Laravel template: the same card markup, the same
 * JSON-LD blocks and the Pilavyer player. Only the URL prefixes differ, so the
 * two sources are thin subclasses of this class.
 */
abstract class MolyAbiSource : SiteSource() {
    override val supportsMovies = true

    /** Archive page used for the "latest" row. Page numbers start at 1. */
    protected abstract fun seriesArchiveUrl(page: Int): String

    /** Movie archive page used for the "latest" row. */
    protected abstract fun movieArchiveUrl(page: Int): String

    protected abstract fun isSeriesUrl(url: String): Boolean

    override suspend fun search(query: String): List<SiteResult> {
        if (query.isBlank()) return emptyList()
        val document = fetchDocument("$mainUrl/ara?q=${encode(query)}") ?: return emptyList()
        return parseCards(document)
    }

    override suspend fun latest(page: Int): List<SiteResult> {
        if (page < 1) return emptyList()
        // The series and movie archives are separate on both sites. Merging them
        // gives the native provider one useful "latest" row of ~48 titles.
        val results = mutableListOf<SiteResult>()
        fetchDocument(seriesArchiveUrl(page))?.let { results.addAll(parseCards(it)) }
        fetchDocument(movieArchiveUrl(page))?.let { results.addAll(parseCards(it)) }
        return results.distinctBy { it.url }
    }

    private fun parseCards(document: Document): List<SiteResult> {
        val results = mutableListOf<SiteResult>()
        for (item in document.select("a.group.block")) {
            val href = absoluteUrl(mainUrl, item.attr("href"))
            if (href.isBlank()) continue
            // Search can also return individual episodes. They are not standalone
            // catalog entries; the series page is the correct target.
            if (href.contains("/bolum/")) continue
            if (href.contains("-sezon-") && href.contains("-bolum")) continue

            val title = item.selectFirst("h3")?.text()?.trim()
                ?: item.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isBlank()) continue

            val year = parseYear(item.selectFirst("p")?.text())
            val poster = item.selectFirst("img")
                ?.firstImage()
                ?.takeIf { it.isNotBlank() }
                ?.let { absoluteUrl(mainUrl, it) }

            results.add(SiteResult(title, href, cardType(item, href), year, poster))
        }
        return results.distinctBy { it.url }
    }

    private fun cardType(item: Element, href: String): TvType {
        val badge = item.selectFirst("span.badge-dizi, span.badge-film")
            ?.text()?.lowercase()
        return when {
            badge?.contains("film") == true -> TvType.Movie
            badge?.contains("dizi") == true -> TvType.TvSeries
            isSeriesUrl(href) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    override suspend fun details(url: String): SiteDetails? {
        val document = fetchDocument(url) ?: return null
        val ld = findJsonLd(document)
        val ldType = ld?.optString("@type").orEmpty()
        val isSeries = when (ldType) {
            "TVSeries" -> true
            "Movie" -> false
            else -> isSeriesUrl(url)
        }

        val name = ld?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()
                ?.replace(Regex("\\s+"), " ")?.trim()
            ?: document.title().substringBefore("|").trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = ld?.optString("image")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = ld?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val dateText = ld?.optString("startDate")?.takeIf { it.isNotBlank() }
            ?: ld?.optString("datePublished")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()
        val year = parseYear(dateText)

        val tags = toStringList(ld?.optJSONArray("genre"))
        val actors = mutableListOf<String>()
        val actorArray = ld?.optJSONArray("actor")
        if (actorArray != null) {
            for (i in 0 until actorArray.length()) {
                val actor = actorArray.optJSONObject(i) ?: continue
                actor.optString("name").takeIf { it.isNotBlank() }?.let { actors.add(it) }
            }
        }
        val score = ld?.optJSONObject("aggregateRating")
            ?.optDouble("ratingValue")
            ?.takeUnless { it.isNaN() || it <= 0.0 }

        val episodes = if (isSeries) parseEpisodes(document, url) else emptyList()

        return SiteDetails(
            name = name,
            url = url,
            type = if (isSeries) TvType.TvSeries else TvType.Movie,
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
     * Both sites render only the selected season's full episode list. The
     * landing page shows just the first (or currently selected) season, so
     * every other season has to be fetched separately.
     */
    private suspend fun parseEpisodes(document: Document, seriesUrl: String): List<SiteEpisode> {
        val cleanUrl = seriesUrl.substringBefore("?").substringBefore("#")
        val seasons = sortedSetOf<Int>()

        for (anchor in document.select("a[href*='sezon=']")) {
            Regex("[?&]sezon=(\\d+)")
                .find(anchor.attr("href"))
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { seasons.add(it) }
        }
        findJsonLd(document)?.optInt("numberOfSeasons", 0)
            ?.takeIf { it > 0 }
            ?.let { count -> for (n in 1..count) seasons.add(n) }
        if (seasons.isEmpty()) seasons.add(1)

        val pages = mutableListOf<Document>()
        // Keep the landing page too. It normally shows only season 1, but it is
        // a free safety net if one of the `?sezon=` requests is refused.
        pages.add(document)
        // Fetch in small batches: a 15-season show otherwise opens 15 requests
        // at once, which several Turkish sites answer with a Cloudflare block.
        for (batch in seasons.toList().chunked(4)) {
            val fetched = coroutineScope {
                batch.map { season ->
                    async {
                        withTimeoutOrNull(15_000) {
                            fetchDocument("$cleanUrl?sezon=$season")
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            pages.addAll(fetched)
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<SiteEpisode>()
        for (page in pages) {
            for (anchor in page.select("a[href]")) {
                val href = absoluteUrl(mainUrl, anchor.attr("href"))
                val match = EPISODE_PATTERN.find(href) ?: continue

                // The hero "İlk Bölüm İzle" button links to the same episode as
                // the first card but has no episode meta paragraph. Skip it
                // before touching the dedupe set, otherwise it would hide the
                // real card for that episode.
                val meta = anchor.selectFirst("p.text-xs")?.text()?.trim() ?: continue
                if (meta.isBlank()) continue
                if (!seen.add(href)) continue

                val season = match.groupValues[1].toIntOrNull() ?: 1
                val number = match.groupValues[2].toIntOrNull()
                val title = anchor.selectFirst("p.truncate")?.text()?.trim()
                val name = if (!title.isNullOrBlank()) "$meta - $title" else meta
                if (name.isBlank()) continue

                result.add(
                    SiteEpisode(
                        name = name,
                        url = href,
                        season = season,
                        episode = number,
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
        val pv = document.selectFirst("[data-pv]")?.attr("data-pv")?.trim().orEmpty()
        if (pv.isNotBlank()) {
            val base = playerBase(document) ?: DEFAULT_PLAYER_BASE
            val playerUrl = "$base/assets/js/s.php?s=${encode(pv)}"
            val html = fetchText(playerUrl, referer = url)
            if (html != null) {
                val stream = playerStream(html)
                if (stream.isNotBlank()) {
                    // The default playlist spreads its segments over the
                    // pilavyer1..6.top shard domains. Several ISPs block or
                    // throttle those hosts, and when one shard dies playback
                    // stops on that segment even though the rest of the file
                    // is healthy - this is the "random buffering" users see.
                    //
                    // The player itself supports an origin mode through
                    // `cdn=0`: the manifest then serves every segment from the
                    // stable play2/pilavyerplay host instead of the shards.
                    // Emit that first and keep the CDN playlist as a manual
                    // fallback for connections where the shards are faster.
                    val origin = withCdnDisabled(stream)
                    emitLink(
                        source = name,
                        label = "Otomatik (HLS)",
                        url = origin,
                        type = ExtractorLinkType.M3U8,
                        referer = url,
                        callback = callback,
                    )
                    if (origin != stream) {
                        emitLink(
                            source = name,
                            label = "HLS (CDN)",
                            url = stream,
                            type = ExtractorLinkType.M3U8,
                            referer = url,
                            callback = callback,
                        )
                    }
                    return true
                }
            }
        }

        val iframe = findPlayerIframe(document)
        if (iframe != null) {
            return extractWithBuiltins(absoluteUrl(mainUrl, iframe), url, callback)
        }
        return false
    }

    private fun playerBase(document: Document): String? {
        for (script in document.select("script[src]")) {
            val src = script.attr("src")
            val marker = "/assets/js/core.js"
            val index = src.indexOf(marker)
            if (index >= 0) {
                val base = src.substring(0, index).ifBlank { mainUrl }
                return absoluteUrl(mainUrl, base).trimEnd('/')
            }
        }
        return null
    }

    private fun playerStream(html: String): String {
        val marker = html.indexOf("window.__PLAYER__")
        if (marker >= 0) {
            val open = html.indexOf('{', marker)
            if (open >= 0) {
                val end = jsonEnd(html, open)
                if (end > open) {
                    val json = runCatching {
                        JSONObject(html.substring(open, end + 1))
                    }.getOrNull()
                    json?.optString("stream")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return cleanPlayerUrl(it).replace("&amp;", "&") }
                }
            }
        }
        // Last-resort support for a player that emits the key without the
        // (already parsed) window.__PLAYER__ assignment.
        val raw = Regex(""""stream"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: return ""
        return cleanPlayerUrl(raw).replace("&amp;", "&")
    }

    /**
     * Turns the shard-based playlist into the player's origin playlist.
     * `cdn=0` is understood by the Pilavyer backend and is propagated into the
     * master playlist, the audio playlists and the segment URLs.
     */
    private fun withCdnDisabled(stream: String): String {
        if (Regex("""[?&]cdn=0(?:&|$)""").containsMatchIn(stream)) return stream
        val separator = if (stream.contains('?')) '&' else '?'
        return "$stream${separator}cdn=0"
    }

    /** Returns the index of the `}` that closes the object opened at [open]. */
    private fun jsonEnd(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
        }
        return -1
    }

    private fun findJsonLd(document: Document): JSONObject? {
        for (script in document.select("script[type=application/ld+json]")) {
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            findTyped(json, 0)?.let { return it }
        }
        return null
    }

    private fun findTyped(node: Any?, depth: Int): JSONObject? {
        if (node == null || depth > 12) return null
        when (node) {
            is JSONObject -> {
                val type = node.optString("@type")
                if (type == "TVSeries" || type == "Movie") return node
                val keys = node.keys()
                while (keys.hasNext()) {
                    findTyped(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findTyped(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun toStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private companion object {
        const val DEFAULT_PLAYER_BASE = "https://play2.pilavyerplay.top"
        val EPISODE_PATTERN = Regex("""-(\d+)-sezon-(\d+)-bolum""")
    }
}

/** DiziMoly (dizimoly.click) — Turkish, Korean, Asian and Western series. */
class DizimolySource : MolyAbiSource() {
    override val key = "dizimoly"
    override val name = "DiziMoly"
    override val mainUrl = "https://www.dizimoly.click"

    override fun seriesArchiveUrl(page: Int): String =
        "$mainUrl/dizi-arsivi/?sirala=yeni&page=$page"

    override fun movieArchiveUrl(page: Int): String =
        "$mainUrl/filmler?sirala=yeni&page=$page"

    override fun isSeriesUrl(url: String): Boolean = url.contains("/kategori/")
}

/** DiziAbi (diziabi.net) — the same engine with different URL prefixes. */
class DiziabiSource : MolyAbiSource() {
    override val key = "diziabi"
    override val name = "DiziAbi"
    override val mainUrl = "https://www.diziabi.net"

    override fun seriesArchiveUrl(page: Int): String =
        "$mainUrl/diziler?page=$page"

    override fun movieArchiveUrl(page: Int): String =
        "$mainUrl/film-arsivi/?sirala=yeni&page=$page"

    override fun isSeriesUrl(url: String): Boolean = url.contains("/dizi/")
}

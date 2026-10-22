package com.turkms

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink

data class SiteResult(
    val name: String,
    val url: String,
    val type: TvType,
    val year: Int? = null,
    val poster: String? = null,
)

data class SiteEpisode(
    val name: String,
    val url: String,
    val season: Int? = null,
    val episode: Int? = null,
    val date: String? = null,
    val poster: String? = null,
)

data class SiteDetails(
    val name: String,
    val url: String,
    val type: TvType,
    val poster: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val tags: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val score: Double? = null,
    val episodes: List<SiteEpisode> = emptyList(),
)

/** A scraped source. The same object powers both the site provider and the TurkMS aggregator. */
abstract class SiteSource {
    abstract val key: String
    abstract val name: String
    abstract val mainUrl: String
    open val supportsMovies: Boolean = false

    open suspend fun search(query: String): List<SiteResult> = emptyList()

    /** Home-page listing used by the per-site providers. */
    open suspend fun latest(page: Int): List<SiteResult> = emptyList()

    open suspend fun details(url: String): SiteDetails? = null

    /** Extracts every playable link from a movie or episode page. */
    open suspend fun loadLinks(
        url: String,
        callback: suspend (ExtractorLink) -> Unit,
    ): Boolean = false
}

suspend fun emitLink(
    source: String,
    label: String,
    url: String,
    type: ExtractorLinkType,
    referer: String,
    callback: suspend (ExtractorLink) -> Unit,
    headers: Map<String, String> = emptyMap(),
) {
    if (url.isBlank()) return
    callback(
        newExtractorLink(source, label, url, type) {
            // Never invent a quality: an unlabeled "auto" stream stays Unknown and is
            // still playable, but it will not pretend to be 1080p.
            this.quality = qualityFromLabel(label)
            this.referer = referer
            this.headers = headers
        }
    )
}

/**
 * Orders links for the "preferred quality" setting: the exact preferred quality first,
 * then everything else by quality (highest first, unknown last).
 */
fun List<ExtractorLink>.sortForPreferredQuality(
    preferred: Int = TurkMSSettings.preferredQuality,
): List<ExtractorLink> {
    fun rank(link: ExtractorLink): Int = when {
        link.quality == preferred -> 0
        link.quality == Qualities.Unknown.value -> 2
        else -> 1
    }

    return sortedWith(
        compareBy<ExtractorLink> { rank(it) }
            .thenByDescending { if (it.quality == Qualities.Unknown.value) 0 else it.quality }
    )
}

/**
 * Hands the URL to every extractor registered inside CloudStream (YouTube, Vimeo, various
 * direct-video hosts, ...). Returns false if no extractor accepted it.
 */
suspend fun extractWithBuiltins(
    url: String,
    referer: String,
    callback: suspend (ExtractorLink) -> Unit,
): Boolean {
    if (url.isBlank()) return false
    val collected = mutableListOf<ExtractorLink>()
    runCatching {
        loadExtractor(url, referer, { /* subtitles are unused */ }) { link ->
            collected.add(link)
        }
    }
    if (collected.isNotEmpty()) {
        for (link in collected) callback(link)
        return true
    }

    val lower = url.lowercase()
    val type = when {
        lower.contains(".m3u8") -> ExtractorLinkType.M3U8
        lower.contains(".mpd") -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }
    emitLink(
        source = "TurkMS",
        label = when (type) {
            ExtractorLinkType.M3U8 -> "HLS"
            ExtractorLinkType.DASH -> "DASH"
            else -> "Video"
        },
        url = url,
        type = type,
        referer = referer,
        callback = callback,
    )
    return true
}

/** Finds the main player iframe, including lazy-loaded WordPress variants. */
fun findPlayerIframe(document: org.jsoup.nodes.Document): String? {
    val iframe = document.selectFirst(
        "div.video-container iframe, div.player iframe, #player iframe, iframe[src*=player], iframe[data-wpfc-original-src], iframe"
    ) ?: return null
    return iframe.attr("data-wpfc-original-src").ifBlank {
        iframe.attr("data-src").ifBlank { iframe.attr("src") }
    }.ifBlank { null }
}

/** Extracts a YouTube video id from all common embed shapes. */
fun youtubeId(url: String): String? {
    if (url.isBlank()) return null
    val patterns = listOf(
        Regex("""youtube\.com/embed/([A-Za-z0-9_-]{6,})"""),
        Regex("""youtube\.com/watch\?v=([A-Za-z0-9_-]{6,})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{6,})"""),
        Regex("""youtube\.html#([A-Za-z0-9_-]{6,})"""),
        Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{6,})"""),
    )
    for (pattern in patterns) {
        pattern.find(url)?.groupValues?.getOrNull(1)?.let { return it }
    }
    return null
}

/** Parses a JW Player `sources: [{file:"...", label:"1080p", type:"mp4"}]` array. */
fun parseJwSources(html: String): List<Triple<String, String, String>> {
    val results = mutableListOf<Triple<String, String, String>>()
    val objectRegex = Regex(
        """\{\s*file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']*)["'](?:[^}]*?type\s*:\s*["']([^"']*)["'])?""",
        RegexOption.IGNORE_CASE
    )
    for (match in objectRegex.findAll(html)) {
        val file = match.groupValues[1]
        val label = match.groupValues[2].ifBlank { "auto" }
        val type = match.groupValues[3].ifBlank {
            if (file.contains(".m3u8")) "hls" else "mp4"
        }
        results.add(Triple(file, label, type))
    }
    return results
}

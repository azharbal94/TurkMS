package com.turkms

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.nicehttp.NiceResponse
import okhttp3.Interceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

const val TURKMS_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

val TURKMS_HEADERS = mapOf(
    "User-Agent" to TURKMS_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
)

/**
 * Headers used by ExoPlayer and the downloader.
 *
 * The User-Agent is intentionally omitted: CloudStream's player already sets a
 * current Chrome User-Agent and reads any per-link User-Agent first, so pinning
 * the plugin's scraping UA here would only make playback look older. The
 * Accept/Accept-Language pair is added because several Turkish CDNs return
 * throttled or empty manifests when ExoPlayer sends no Accept header at all.
 * Per-link headers (for example Twitter's Referer) still win because
 * [com.turkms.emitLink] merges this map first.
 */
val TURKMS_PLAYBACK_HEADERS = mapOf(
    "Accept" to "*/*",
    "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
)

/**
 * Cloudflare's JavaScript challenge and "blocked" pages are served as normal
 * 200/403 HTML documents, so a scraper that only checks for a non-null body
 * silently parses an empty page. These markers are specific to those pages;
 * do not match `challenge-platform` because Cloudflare injects that script into
 * perfectly valid pages too.
 */
private fun looksLikeCloudflareBlock(html: String?): Boolean {
    if (html.isNullOrBlank()) return false
    val head = html.take(6000)
    return head.contains("Just a moment", ignoreCase = true) ||
        head.contains("cf-browser-verification", ignoreCase = true) ||
        head.contains("Attention Required", ignoreCase = true) ||
        head.contains("Enable JavaScript and cookies to continue", ignoreCase = true) ||
        head.contains("Checking your browser before accessing", ignoreCase = true)
}

/** One shared killer so the cookies it obtains survive between requests. */
private val cloudflareKiller: CloudflareKiller by lazy { CloudflareKiller() }

private suspend fun getResponse(
    url: String,
    referer: String?,
    headers: Map<String, String>,
    interceptor: Interceptor? = null,
): NiceResponse? = runCatching {
    app.get(
        url,
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
        interceptor = interceptor,
    )
}.getOrNull()

private suspend fun getWithCloudflare(
    url: String,
    referer: String?,
    headers: Map<String, String>,
): NiceResponse? {
    val first = getResponse(url, referer, headers) ?: return null
    val body = runCatching { first.text }.getOrNull()
    if (!looksLikeCloudflareBlock(body)) return first
    // Only a real challenge is retried; a dead origin (HTTP 520) is not
    // something a WebView can solve and retrying would just waste time.
    return runCatching { getResponse(url, referer, headers, cloudflareKiller) }.getOrNull() ?: first
}

private suspend fun postResponse(
    url: String,
    data: Map<String, String>,
    referer: String?,
    headers: Map<String, String>,
    interceptor: Interceptor? = null,
): NiceResponse? = runCatching {
    app.post(
        url,
        data = data,
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
        interceptor = interceptor,
    )
}.getOrNull()

private suspend fun postWithCloudflare(
    url: String,
    data: Map<String, String>,
    referer: String?,
    headers: Map<String, String>,
): NiceResponse? {
    val first = postResponse(url, data, referer, headers) ?: return null
    val body = runCatching { first.text }.getOrNull()
    if (!looksLikeCloudflareBlock(body)) return first
    return runCatching { postResponse(url, data, referer, headers, cloudflareKiller) }.getOrNull() ?: first
}

suspend fun fetchDocument(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): Document? = getWithCloudflare(url, referer, headers)?.document

suspend fun postDocument(
    url: String,
    data: Map<String, String>,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): Document? = postWithCloudflare(url, data, referer, headers)?.document

suspend fun fetchText(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): String? = getWithCloudflare(url, referer, headers)?.text

suspend fun postText(
    url: String,
    data: Map<String, String>,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): String? = postWithCloudflare(url, data, referer, headers)?.text

suspend fun postJsonText(
    url: String,
    body: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): String? = runCatching {
    app.post(
        url,
        json = JsonAsString(body),
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
    ).text
}.getOrNull()

fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

fun absoluteUrl(base: String, url: String?): String {
    if (url.isNullOrBlank()) return ""
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    if (url.startsWith("//")) return "https:$url"
    return try {
        java.net.URI(base).resolve(url).toString()
    } catch (_: Exception) {
        base.trimEnd('/') + "/" + url.trimStart('/')
    }
}

fun Element.abs(attr: String, base: String): String =
    absoluteUrl(base, attr(attr).ifBlank { attr("data-src") })

fun Element.firstImage(): String {
    // Most Turkish sites lazy-load cards. The src is then a 1x1 data URI and
    // the real poster is in data-src/data-lazy-src, so prefer the lazy value.
    val lazy = attr("data-src").ifBlank { attr("data-lazy-src") }
    return lazy.ifBlank { attr("src") }
}

fun String.stripHtml(): String =
    org.jsoup.Jsoup.parse(this).text().replace(Regex("\\s+"), " ").trim()

fun normalizeTitle(value: String): String {
    return value.lowercase()
        .replace("ı", "i")
        .replace("İ", "i")
        .replace("ş", "s")
        .replace("ğ", "g")
        .replace("ü", "u")
        .replace("ö", "o")
        .replace("ç", "c")
        .replace("â", "a")
        .replace("î", "i")
        .replace("û", "u")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(
            Regex(
                "\\b(izle|full|hd|tek|parca|son|bolum|bolumu|film|filmleri|dizi|dizisi|turkce|dublaj|altyazili|yeni|sezon|final)\\b"
            ),
            " "
        )
        .replace(Regex("\\s+"), " ")
        .trim()
}

fun titleScore(a: String, b: String): Int {
    val left = normalizeTitle(a)
    val right = normalizeTitle(b)
    if (left.isBlank() || right.isBlank()) return 0
    if (left == right) return 100
    if (left.contains(right) || right.contains(left)) return 85
    val leftWords = left.split(" ").filter { it.length > 2 }.toSet()
    val rightWords = right.split(" ").filter { it.length > 2 }.toSet()
    if (leftWords.isEmpty() || rightWords.isEmpty()) return 0
    val common = leftWords.intersect(rightWords).size
    return common * 20
}

/**
 * True when a media URL answers with a playable 200/206 response. A tiny range
 * request keeps the check cheap; it is used to offer optional lower-bitrate
 * renditions without ever handing the player a dead URL.
 */
suspend fun mediaPlayable(url: String, referer: String? = null): Boolean = runCatching {
    app.get(
        url,
        headers = TURKMS_HEADERS + mapOf(
            "Range" to "bytes=0-0",
            "Accept" to "*/*",
        ),
        referer = referer,
        timeout = 8_000,
    ).code in 200..206
}.getOrDefault(false)

fun parseYear(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    return Regex("(19|20)\\d{2}").find(text)?.value?.toIntOrNull()
}

fun parseEpisodeNumber(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val patterns = listOf(
        Regex("(?i)(\\d+)\\s*[.\\-]?\\s*B[oö]l[uü]m"),
        Regex("(?i)b[oö]l[uü]m\\s*[.:\\-]?\\s*(\\d+)"),
        Regex("(?i)(\\d+)\\s*[.\\-]\\s*sezon"),
    )
    for (pattern in patterns) {
        pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    }
    return null
}

fun parseSeasonNumber(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val pattern = Regex("(?i)(\\d+)\\s*[.\\-]?\\s*sezon")
    return pattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

fun qualityFromLabel(label: String?): Int {
    val value = label?.lowercase() ?: return com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    return when {
        value.contains("2160") || value.contains("4k") -> 2160
        value.contains("1440") -> 1440
        value.contains("1080") -> 1080
        value.contains("720") -> 720
        value.contains("480") -> 480
        value.contains("360") -> 360
        value.contains("240") -> 240
        else -> com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }
}

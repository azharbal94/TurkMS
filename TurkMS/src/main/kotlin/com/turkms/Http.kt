package com.turkms

import com.lagradost.cloudstream3.app
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

suspend fun fetchDocument(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): Document? = runCatching {
    app.get(
        url,
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
    ).document
}.getOrNull()

suspend fun postDocument(
    url: String,
    data: Map<String, String>,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): Document? = runCatching {
    app.post(
        url,
        data = data,
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
    ).document
}.getOrNull()

suspend fun fetchText(
    url: String,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): String? = runCatching {
    app.get(
        url,
        headers = TURKMS_HEADERS + headers,
        referer = referer,
        timeout = TurkMSSettings.requestTimeout,
    ).text
}.getOrNull()

suspend fun postText(
    url: String,
    data: Map<String, String>,
    referer: String? = null,
    headers: Map<String, String> = emptyMap(),
): String? = runCatching {
    app.post(
        url,
        data = data,
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

fun Element.firstImage(): String =
    attr("src").ifBlank { attr("data-src") }.ifBlank { attr("data-lazy-src") }

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

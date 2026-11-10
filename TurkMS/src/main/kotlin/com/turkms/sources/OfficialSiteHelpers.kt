package com.turkms.sources

import com.turkms.absoluteUrl
import com.turkms.firstImage
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * Helpers shared by the official broadcaster sites (NOW, Kanal D, atv, Show TV,
 * TRT 1, Star TV) and izle7.
 *
 * All of those sites publish their metadata as schema.org JSON-LD and most of
 * their stream URLs as `VideoObject.contentUrl`, so a small, tolerant reader is
 * enough. Keeping it here avoids seven slightly different copies.
 */

/** Reads `<loc>` entries from a sitemap, including CDATA-wrapped values. */
internal fun sitemapLocations(xml: String?): List<String> {
    if (xml.isNullOrBlank()) return emptyList()
    return runCatching {
        Jsoup.parse(xml, "", Parser.xmlParser())
            .select("loc")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

/** Flattens every JSON-LD object on the page, including `@graph` members. */
internal fun parseJsonLd(document: Document): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    for (script in document.select("script[type=application/ld+json]")) {
        val raw = script.data().ifBlank { script.html() }.trim()
        if (raw.isBlank()) continue
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: continue
        collectJsonLd(root, result, 0)
    }
    return result
}

private fun collectJsonLd(obj: JSONObject, out: MutableList<JSONObject>, depth: Int) {
    if (depth > 5) return
    out.add(obj)
    obj.optJSONArray("@graph")?.let { graph ->
        for (i in 0 until graph.length()) {
            graph.optJSONObject(i)?.let { collectJsonLd(it, out, depth + 1) }
        }
    }
    obj.optJSONArray("itemListElement")?.let { list ->
        for (i in 0 until list.length()) {
            list.optJSONObject(i)?.let { collectJsonLd(it, out, depth + 1) }
        }
    }
}

private fun jsonLdTypes(obj: JSONObject): List<String> {
    val raw = obj.opt("@type") ?: return emptyList()
    return when (raw) {
        is String -> listOf(raw)
        is JSONArray -> (0 until raw.length())
            .mapNotNull { raw.optString(it).takeIf { value -> value.isNotBlank() } }

        else -> emptyList()
    }
}

internal fun jsonLdOfType(document: Document, type: String): JSONObject? =
    parseJsonLd(document).firstOrNull { obj ->
        jsonLdTypes(obj).any { it.equals(type, ignoreCase = true) }
    }

/** All `contentUrl` values, including the array/object variants schema.org allows. */
internal fun jsonLdContentUrls(document: Document): List<String> {
    val result = mutableListOf<String>()
    for (obj in parseJsonLd(document)) collectJsonLdUrls(obj.opt("contentUrl"), result)
    return result.filter { it.isNotBlank() }.distinct()
}

private fun collectJsonLdUrls(value: Any?, out: MutableList<String>) {
    when (value) {
        is String -> if (value.startsWith("http")) out.add(value)
        is JSONArray -> for (i in 0 until value.length()) collectJsonLdUrls(value.opt(i), out)
        is JSONObject -> {
            collectJsonLdUrls(value.opt("url"), out)
            collectJsonLdUrls(value.opt("contentUrl"), out)
        }
    }
}

/** Pulls the first poster URL out of any JSON-LD object that has an `image`. */
internal fun jsonLdImage(document: Document): String? {
    for (obj in parseJsonLd(document)) {
        val image = obj.opt("image") ?: continue
        val url = when (image) {
            is String -> image
            is JSONObject -> image.optString("url").ifBlank { image.optString("contentUrl") }
            is JSONArray -> {
                val first = image.opt(0)
                when (first) {
                    is String -> first
                    is JSONObject -> first.optString("url")
                    else -> null
                }
            }

            else -> null
        }
        if (!url.isNullOrBlank()) return url
    }
    return null
}

internal fun jsonLdString(document: Document, type: String, key: String): String? =
    jsonLdOfType(document, type)?.optString(key)?.trim()?.takeIf { it.isNotBlank() }

/**
 * Extracts an inline JSON array that follows `"key":[` in a script.
 *
 * Several Turkish portals embed their Vue/Next.js payload directly in the HTML
 * as a JSON string. A bracket matcher that respects quoted strings is enough to
 * pull the array out without guessing where it ends.
 */
internal fun extractJsonArray(html: String, key: String): JSONArray? {
    val marker = "\"$key\":["
    val markerIndex = html.indexOf(marker)
    if (markerIndex < 0) return null
    val start = markerIndex + marker.length - 1
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until html.length) {
        val ch = html[index]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val raw = html.substring(start, index + 1)
                        return runCatching { JSONArray(raw) }.getOrNull()
                    }
                }
            }
        }
    }
    return null
}

/** "Halef-Koklerin-Cagrisi" -> "Halef Koklerin Cagrisi" as a last-resort label. */
internal fun titleFromSlug(url: String): String {
    val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
    if (slug.isBlank()) return ""
    return slug.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}

/** Turns JSON escape sequences (`\u0130`, `\/`) into normal text. */
internal fun unescapeJson(value: String): String {
    if (value.isBlank()) return ""
    return runCatching {
        JSONObject("{\"v\":\"$value\"}").optString("v")
    }.getOrDefault(value)
}

internal fun Document.metaContent(vararg names: String): String? {
    for (name in names) {
        val value = selectFirst("meta[property=\"$name\"], meta[name=\"$name\"]")
            ?.attr("content")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

internal fun Document.firstText(vararg selectors: String): String? {
    for (selector in selectors) {
        val value = selectFirst(selector)?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

/** Resolves a page image and returns it absolute, or null. */
internal fun Document.posterFromImage(
    mainUrl: String,
    title: String? = null,
): String? {
    val images = select("img")
    if (!title.isNullOrBlank()) {
        val matching: org.jsoup.nodes.Element? = images.firstOrNull { img ->
            img.attr("alt").trim().equals(title.trim(), ignoreCase = true)
        }
        if (matching != null) {
            val value = matching.firstImage()
            if (value.isNotBlank()) return absoluteUrl(mainUrl, value)
        }
    }
    val og = metaContent("og:image", "twitter:image")
    return og?.takeIf { it.isNotBlank() }
}

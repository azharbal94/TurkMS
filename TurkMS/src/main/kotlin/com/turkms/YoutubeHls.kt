package com.turkms

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

/**
 * YouTube stream resolver.
 *
 * Since 2026 the normal web player response is SABR-only: it contains no
 * direct `formats[*].url` or `signatureCipher` values. CloudStream's bundled
 * NewPipe version therefore cannot play many YouTube videos (and the
 * DiziIzleNow provider only embeds YouTube). The visionOS InnerTube client is
 * still allowed to return an adaptive HLS manifest, so we ask that client
 * first and fall back to CloudStream's extractor only when necessary.
 *
 * The visitor id is required. A normal YouTube embed page is small (~150 KB)
 * and exposes it as `"VISITOR_DATA":"..."`.
 */
private const val YOUTUBE_PLAYER_URL =
    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
private const val VISIONOS_UA =
    "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)"
private const val YOUTUBE_COOKIE = "CONSENT=YES+cb; SOCS=CAI"
private const val VISITOR_TTL_MS = 30 * 60 * 1000L

@Volatile
private var cachedVisitorData: String? = null

@Volatile
private var cachedVisitorAt: Long = 0L

/** Returns an adaptive HLS link for a YouTube video, or false if unavailable. */
suspend fun extractYoutubeHls(
    videoId: String,
    callback: suspend (ExtractorLink) -> Unit,
): Boolean {
    if (videoId.isBlank()) return false

    val visitor = visitorData(videoId) ?: return false
    val body = JSONObject()
        .put(
            "context",
            JSONObject()
                .put(
                    "client",
                    JSONObject()
                        .put("clientName", "VISIONOS")
                        .put("clientVersion", "1.02")
                        .put("clientScreen", "WATCH")
                        .put("hl", "en")
                        .put("gl", "US")
                        .put("visitorData", visitor),
                )
                .put(
                    "device",
                    JSONObject()
                        .put("platform", "MOBILE")
                        .put("osName", "visionOS")
                        .put("osVersion", "25.6.0.23O471")
                        .put("deviceMake", "Apple")
                        .put("deviceModel", "RealityDevice14,1"),
                ),
        )
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)

    val response = runCatching {
        postJsonText(
            YOUTUBE_PLAYER_URL,
            body.toString(),
            referer = "https://www.youtube.com/",
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to VISIONOS_UA,
                "X-Youtube-Client-Name" to "101",
                "X-Youtube-Client-Version" to "1.02",
                "X-Goog-Visitor-Id" to visitor,
                "X-Goog-Api-Format-Version" to "2",
                "Origin" to "https://www.youtube.com",
            ),
        )
    }.getOrNull() ?: return false

    val root = runCatching { JSONObject(response) }.getOrNull() ?: return false
    if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return false

    val streaming = root.optJSONObject("streamingData") ?: return false
    val hls = streaming.optString("hlsManifestUrl")
    if (hls.isNotBlank()) {
        emitLink(
            source = "YouTube",
            label = "Auto (HLS)",
            url = hls,
            type = ExtractorLinkType.M3U8,
            referer = "https://www.youtube.com/",
            callback = callback,
            headers = mapOf("Referer" to "https://www.youtube.com/"),
        )
        return true
    }

    // Older/less restricted videos can still expose a muxed MP4 stream.
    val formats = streaming.optJSONArray("formats") ?: return false
    var emitted = false
    for (i in 0 until formats.length()) {
        val format = formats.optJSONObject(i) ?: continue
        val url = format.optString("url")
        if (url.isBlank()) continue
        val label = format.optString("qualityLabel").ifBlank { "Video" }
        emitLink(
            source = "YouTube",
            label = label,
            url = url,
            type = ExtractorLinkType.VIDEO,
            referer = "https://www.youtube.com/",
            callback = callback,
        )
        emitted = true
    }
    return emitted
}

/** Resolves YouTube through visionOS first, then CloudStream's built-in extractor. */
suspend fun extractYoutubeWithFallback(
    videoId: String,
    referer: String,
    callback: suspend (ExtractorLink) -> Unit,
): Boolean {
    if (extractYoutubeHls(videoId, callback)) return true

    val watchUrl = "https://www.youtube.com/watch?v=$videoId"
    if (extractBuiltinOnly(watchUrl, referer, callback)) return true
    return extractBuiltinOnly("https://www.youtube.com/embed/$videoId", referer, callback)
}

/**
 * CloudStream's generic helper emits the input URL as a last resort. That is
 * useful for direct HLS links, but a YouTube watch page is not playable, so
 * only accept links an actual extractor produced here.
 */
private suspend fun extractBuiltinOnly(
    url: String,
    referer: String,
    callback: suspend (ExtractorLink) -> Unit,
): Boolean {
    val collected = mutableListOf<ExtractorLink>()
    runCatching {
        loadExtractor(url, referer, { }) { link -> collected.add(link) }
    }
    if (collected.isEmpty()) return false
    for (link in collected) callback(link)
    return true
}

private suspend fun visitorData(videoId: String): String? {
    val now = System.currentTimeMillis()
    val cached = cachedVisitorData
    if (!cached.isNullOrBlank() && now - cachedVisitorAt < VISITOR_TTL_MS) return cached

    val headers = mapOf(
        "Cookie" to YOUTUBE_COOKIE,
        "Accept-Language" to "en-US,en;q=0.9",
    )
    val sources = listOf(
        "https://www.youtube.com/embed/$videoId?hl=en&gl=US",
        "https://www.youtube.com/watch?v=$videoId&hl=en&gl=US",
        "https://www.youtube.com/",
    )
    for (url in sources) {
        val html = fetchText(url, referer = "https://www.youtube.com/", headers = headers)
            ?: continue
        val match = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)
            ?: continue
        val value = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: continue
        cachedVisitorData = value
        cachedVisitorAt = now
        return value
    }
    return null
}

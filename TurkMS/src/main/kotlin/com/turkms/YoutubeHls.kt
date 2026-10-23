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

    // YouTube's adaptive HLS manifest is the only unthrottled path. The raw
    // adaptiveFormats URLs are rate-limited to roughly 3 Mbps no matter what
    // headers are sent, which buffers on everything above 720p; HLS segments
    // are served at full speed. The master still advertises VP9 and AV1
    // renditions, and ExoPlayer may select one that a weak hardware decoder
    // cannot sustain, so we rewrite the master to keep only H.264 variants and
    // serve it from the loopback server.
    val hls = streaming.optString("hlsManifestUrl")
    if (hls.isNotBlank()) {
        val local = filteredAvcHlsUrl(hls)
        emitLink(
            source = "YouTube",
            label = if (local != null) "Auto (HLS H.264)" else "Auto (HLS)",
            url = local ?: hls,
            type = ExtractorLinkType.M3U8,
            referer = "https://www.youtube.com/",
            callback = callback,
            headers = mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            ),
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

/**
 * Downloads YouTube's HLS master, drops every VP9/AV1 variant (and any audio
 * group that is no longer referenced) and returns a loopback URL for the
 * rewritten playlist. Returns null when anything goes wrong so the caller can
 * fall back to the original manifest.
 */
private suspend fun filteredAvcHlsUrl(hls: String): String? {
    val master = runCatching {
        fetchText(
            hls,
            referer = "https://www.youtube.com/",
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            ),
        )
    }.getOrNull() ?: return null
    val filtered = filterAvcMaster(master, hls) ?: return null
    return runCatching { serveLocalPlaylist(filtered) }.getOrNull()
}

/** Keeps only `avc1` `#EXT-X-STREAM-INF` entries plus the audio groups they use. */
private fun filterAvcMaster(master: String, baseUrl: String): String? {
    val lines = master.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val keptGroups = linkedSetOf<String>()
    val variants = mutableListOf<Pair<String, String>>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trim()
        if (line.startsWith("#EXT-X-STREAM-INF")) {
            val attrs = line.substringAfter(':', "")
            val uri = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (uri.isNotEmpty() && !uri.startsWith("#") && attrs.contains("avc1", ignoreCase = true)) {
                Regex("""AUDIO="([^"]*)"""").find(attrs)
                    ?.groupValues?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { keptGroups.add(it) }
                variants.add(attrs to uri)
            }
            index += 2
            continue
        }
        index++
    }
    if (variants.isEmpty()) return null

    val out = mutableListOf<String>()
    out.add("#EXTM3U")
    out.add("#EXT-X-VERSION:3")
    out.add("#EXT-X-INDEPENDENT-SEGMENTS")
    for (line in lines) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#EXT-X-MEDIA:")) continue
        val type = Regex("""TYPE=([^,"]*)""").find(trimmed)
            ?.groupValues?.getOrNull(1)?.trim()?.uppercase() ?: ""
        if (type != "AUDIO") continue
        val group = Regex("""GROUP-ID="([^"]*)"""").find(trimmed)?.groupValues?.getOrNull(1)
        if (!group.isNullOrBlank() && group !in keptGroups) continue
        out.add(rewriteMediaUri(trimmed, baseUrl))
    }
    for ((attrs, uri) in variants) {
        out.add("#EXT-X-STREAM-INF:$attrs")
        out.add(absolutePlaylistUri(uri, baseUrl))
    }
    return out.joinToString("\n") + "\n"
}

/** Rewrites the URI attribute of an `#EXT-X-MEDIA` line to an absolute URL. */
private fun rewriteMediaUri(line: String, baseUrl: String): String {
    val uri = Regex("""URI="([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: return line
    val absolute = absolutePlaylistUri(uri, baseUrl)
    return if (absolute == uri) line else line.replace("""URI="$uri"""", """URI="$absolute"""")
}

/** Resolves a possibly relative playlist URL against the remote master URL. */
private fun absolutePlaylistUri(uri: String, baseUrl: String): String {
    val value = uri.trim()
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    val schemeEnd = baseUrl.indexOf("://")
    if (schemeEnd <= 0) return value
    val scheme = baseUrl.substring(0, schemeEnd)
    val afterScheme = baseUrl.substring(schemeEnd + 3)
    val hostEnd = afterScheme.indexOf('/')
    val host = if (hostEnd >= 0) afterScheme.substring(0, hostEnd) else afterScheme
    return if (value.startsWith("/")) {
        "$scheme://$host$value"
    } else {
        "${baseUrl.substringBeforeLast('/')}/$value"
    }
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

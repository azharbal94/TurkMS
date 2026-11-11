package com.turkms

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Extractor for the FirePlayer used by canlidizi14, trdiziizle and canliplayer.
 *
 * The player page loads its streams from `?do=getVideo`:
 *
 *   POST https://canliplayer.com/fireplayer/video/<hash>?do=getVideo
 *   hash=<hash>&r=<episode page>&s=<source>
 *
 * The response contains `videoSources` (file/label/type) and a `sourceList` of
 * alternative servers. Both are collected, which gives more qualities (often
 * 1080p) and more playback options than the first iframe alone.
 */
suspend fun extractFirePlayer(
    playerUrl: String,
    referer: String,
    sourceName: String,
    callback: suspend (ExtractorLink) -> Unit,
): Boolean {
    val hash = Regex("""/fireplayer/video/([^/?#]+)""")
        .find(playerUrl)?.groupValues?.getOrNull(1) ?: return false
    val api = playerUrl.substringBefore('?').trimEnd('/') + "?do=getVideo"

    val main = fetchFireSources(api, hash, referer, null) ?: return false
    val seen = mutableSetOf<String>()
    var emitted = false

    suspend fun emitSources(sources: List<FireSource>) {
        for (source in sources) {
            if (source.url.isBlank() || !seen.add(source.url)) continue

            // Some servers expose a YouTube mirror instead of a direct stream.
            val ytId = youtubeId(source.url)
            if (ytId != null) {
                val ok = extractWithBuiltins(
                    "https://www.youtube.com/watch?v=$ytId",
                    referer,
                    callback,
                )
                if (ok) emitted = true
                continue
            }

            emitLink(
                source = sourceName,
                label = source.label,
                url = source.url,
                type = source.type,
                referer = playerUrl,
                callback = callback,
                headers = mapOf("Referer" to playerUrl),
            )
            emitted = true
        }
    }

    // The primary server is enough to start playback; emit it before spending
    // time on the alternative servers.
    emitSources(parseFireSources(main))

    // Alternative servers listed by the player. Fetching two of them keeps the
    // extra choice without turning a single episode into a long request chain.
    val sourceList = main.optJSONObject("sourceList")
    if (sourceList != null) {
        val current = main.optString("sIndex")
        val keys = sourceList.keys().asSequence()
            .filter { it != current }
            .take(2)
            .toList()
        val alternates = coroutineScope {
            keys.map { key ->
                async {
                    withTimeoutOrNull(8_000) {
                        fetchFireSources(api, hash, referer, key)
                    }
                }
            }.awaitAll()
        }
        for (json in alternates) {
            if (json != null) emitSources(parseFireSources(json))
        }
    }

    return emitted
}

private data class FireSource(
    val url: String,
    val label: String,
    val type: ExtractorLinkType,
)

private suspend fun fetchFireSources(
    api: String,
    hash: String,
    referer: String,
    source: String?,
): JSONObject? = runCatching {
    val body = postText(
        api,
        data = buildMap {
            put("hash", hash)
            put("r", referer)
            if (source != null) put("s", source)
        },
        referer = referer,
        headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        ),
    ) ?: return null

    JSONObject(body)
}.getOrNull()

private fun parseFireSources(json: JSONObject): List<FireSource> {
    val array = json.optJSONArray("videoSources") ?: return emptyList()
    val result = mutableListOf<FireSource>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        var file = item.optString("file").replace("\\/", "/")
        if (file.isBlank()) continue
        if (file.startsWith("//")) file = "https:$file"
        // The player itself rewrites this placeholder before playback.
        if (file.contains("GenYouTube.net")) {
            file = file.replace("GenYouTube.net", "canliplayer.com")
        }

        val rawType = item.optString("type").lowercase()
        val lower = file.lowercase()
        val type = when {
            rawType.contains("hls") || rawType.contains("mpegurl") ||
                lower.contains(".m3u8") -> ExtractorLinkType.M3U8

            rawType.contains("dash") || lower.contains(".mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        val label = item.optString("label").takeIf { it.isNotBlank() } ?: "Video"
        result.add(FireSource(file, label, type))
    }
    return result
}

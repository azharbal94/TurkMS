package com.turkms

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** A subtitle URL that the player can actually load, plus any required headers. */
data class ResolvedSubtitle(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Turns subtitle downloads into playable SRT/VTT URLs.
 *
 * SubDL, SubSource and OpenSubtitles commonly return ZIP/GZIP archives.
 * CloudStream hands subtitle URLs to an OkHttp data source, so file://, data:
 * and zip:// URLs cannot be used. This resolver downloads an archive, extracts
 * the best-matching subtitle, normalises its encoding to UTF-8 and serves it
 * from a tiny loopback HTTP server for the duration of the session.
 *
 * Plain .srt/.vtt URLs are passed through untouched, so SubtitleCat, Wyzie and
 * the OpenSubtitles Stremio addon keep their zero-copy fast path.
 */
object SubtitleResolver {
    private const val MAX_DOWNLOAD_BYTES = 6 * 1024 * 1024
    private const val MAX_SUBTITLE_BYTES = 3 * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRY_BYTES = 2 * 1024 * 1024
    private const val MAX_ARCHIVE_TOTAL_BYTES = 5 * 1024 * 1024
    private const val RESOLVE_TTL_MS = 30 * 60 * 1000L

    private data class CacheEntry(val value: ResolvedSubtitle, val at: Long)
    private data class ExtractedSubtitle(val text: String, val extension: String)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun resolve(
        url: String,
        headers: Map<String, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
    ): ResolvedSubtitle? {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return null
        if (isPlainSubtitleUrl(cleanUrl)) return ResolvedSubtitle(cleanUrl, headers)

        val cacheKey = buildString {
            append(cleanUrl)
            append('|')
            append(headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" })
            append('|')
            append(season ?: 0)
            append('|')
            append(episode ?: 0)
        }
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.at <= RESOLVE_TTL_MS) return entry.value
            cache.remove(cacheKey)
        }

        val response = runCatching {
            app.get(
                cleanUrl,
                headers = TURKMS_HEADERS + mapOf("Accept" to "*/*") + headers,
                timeout = TurkMSSettings.requestTimeout,
            )
        }.getOrNull() ?: return null

        val bytes = withContext(Dispatchers.IO) {
            runCatching { response.body?.bytes() }.getOrNull()
        } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_DOWNLOAD_BYTES) return null

        val extracted = withContext(Dispatchers.IO) {
            extract(bytes, season, episode)
        } ?: return null
        if (extracted.text.length > MAX_SUBTITLE_BYTES) return null
        val body = extracted.text.toByteArray(StandardCharsets.UTF_8)
        if (body.isEmpty() || body.size > MAX_SUBTITLE_BYTES) return null

        val proxied = SubtitleProxy.serve(body, extracted.extension) ?: return null
        val value = ResolvedSubtitle(proxied)
        cache[cacheKey] = CacheEntry(value, System.currentTimeMillis())
        return value
    }

    private fun isPlainSubtitleUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US).substringBefore('?').substringBefore('#')
        return lower.endsWith(".srt") || lower.endsWith(".vtt")
    }

    private fun extract(bytes: ByteArray, season: Int?, episode: Int?): ExtractedSubtitle? {
        // ZIP magic.
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return decodeSubtitle(extractZip(bytes, season, episode) ?: return null)
        }
        // GZIP magic.
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            val unzipped = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { readLimited(it, MAX_SUBTITLE_BYTES) }
            }.getOrNull() ?: return null
            return decodeSubtitle(unzipped)
        }
        return decodeSubtitle(bytes)
    }

    /**
     * Picks the best subtitle file inside a ZIP. It prefers an exact
     * season/episode match, then SRT over VTT over ASS/SSA.
     */
    private fun extractZip(bytes: ByteArray, season: Int?, episode: Int?): ByteArray? {
        return runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var best: ByteArray? = null
                var bestScore = Int.MIN_VALUE
                var total = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val lower = name.lowercase(Locale.US)
                    val ext = lower.substringAfterLast('.', "")
                    if (ext !in setOf("srt", "vtt", "ass", "ssa")) continue
                    val data = readLimited(zip, MAX_ARCHIVE_ENTRY_BYTES) ?: continue
                    total += data.size
                    if (total > MAX_ARCHIVE_TOTAL_BYTES) return null

                    var score = when (ext) {
                        "srt" -> 30
                        "vtt" -> 20
                        else -> 10
                    }
                    val marked = parseSeasonEpisode(name)
                    if (season != null && episode != null) {
                        if (marked != null) {
                            if (marked != (season to episode)) continue
                            score += 100
                        } else {
                            // An unmarked file may be a movie or a season pack. It is
                            // only a fallback when no exact episode file exists.
                            score -= 20
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score
                        best = data
                    }
                    if (score >= 130) break
                }
                best
            }
        }.getOrNull()
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeSubtitle(bytes: ByteArray): ExtractedSubtitle? {
        if (bytes.isEmpty()) return null
        val text = decodeText(bytes)
        if (text.isBlank()) return null
        val trimmed = text.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
        return when {
            trimmed.startsWith("WEBVTT", ignoreCase = true) ->
                ExtractedSubtitle(text, "vtt")

            trimmed.contains("[Script Info]", ignoreCase = true) ||
                trimmed.contains("[Events]", ignoreCase = true) -> {
                val srt = assToSrt(text) ?: return null
                ExtractedSubtitle(srt, "srt")
            }

            text.contains("-->") -> ExtractedSubtitle(text, "srt")
            else -> null
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16LE"))
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charset.forName("UTF-16BE"))
        }

        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null) return utf8

        // Legacy Arabic subtitle files are usually Windows-1256. Turkish
        // sources sometimes use Windows-1254, and the rest are CP1252.
        val arabic = runCatching { String(bytes, Charset.forName("windows-1256")) }.getOrNull()
        if (arabic != null && arabic.any { it in '\u0600'..'\u06FF' }) return arabic
        val turkish = runCatching { String(bytes, Charset.forName("windows-1254")) }.getOrNull()
        if (turkish != null && turkish.any { it in "şğıİöüçŞĞIÖÜÇ" }) return turkish
        return runCatching { String(bytes, Charset.forName("windows-1252")) }
            .getOrElse { String(bytes, StandardCharsets.ISO_8859_1) }
    }

    /** Minimal ASS/SSA to SRT conversion for subtitles packed by SubSource/SubDL. */
    private fun assToSrt(text: String): String? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var inEvents = false
        var format: List<String> = emptyList()
        val blocks = mutableListOf<String>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                format = line.substringAfter(':').split(',')
                    .map { it.trim().lowercase(Locale.US) }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue

            val payload = line.substringAfter(':').trim()
            val textIndex = format.indexOf("text").let { if (it >= 0) it else 9 }
            val fields = splitAssFields(payload, maxOf(format.size, textIndex + 1))
            val startIndex = format.indexOf("start").let { if (it >= 0) it else 1 }
            val endIndex = format.indexOf("end").let { if (it >= 0) it else 2 }
            val start = parseAssTime(fields.getOrNull(startIndex) ?: continue) ?: continue
            val end = parseAssTime(fields.getOrNull(endIndex) ?: continue) ?: continue
            val rawText = fields.getOrNull(textIndex) ?: continue
            val body = rawText
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (body.isBlank()) continue
            blocks.add(
                "${blocks.size + 1}\n" +
                    "${formatSrtTime(start)} --> ${formatSrtTime(end)}\n" +
                    body
            )
        }
        return blocks.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** Splits ASS dialogue fields without cutting commas that belong to the text. */
    private fun splitAssFields(payload: String, count: Int): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        for (i in 0 until count - 1) {
            val comma = payload.indexOf(',', start)
            if (comma < 0) {
                result.add(payload.substring(start))
                return result
            }
            result.add(payload.substring(start, comma))
            start = comma + 1
        }
        result.add(payload.substring(start))
        return result
    }

    private fun parseAssTime(value: String): Long? {
        val parts = value.trim().replace(',', '.').split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong())
    }

    private fun formatSrtTime(milliseconds: Long): String {
        val total = milliseconds.coerceAtLeast(0)
        val hours = total / 3_600_000
        val minutes = (total % 3_600_000) / 60_000
        val seconds = (total % 60_000) / 1000
        val millis = total % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}

/**
 * Serves an in-memory HLS master playlist from the same loopback server used
 * for extracted subtitles. CloudStream's player only understands http(s), and
 * rewriting YouTube's master is the only way to keep ExoPlayer from choosing a
 * VP9/AV1 rendition that a low-end decoder cannot sustain.
 */
suspend fun serveLocalPlaylist(text: String): String? {
    if (text.isBlank()) return null
    return SubtitleProxy.serve(text.toByteArray(StandardCharsets.UTF_8), "m3u8")
}

private val SUBTITLE_EPISODE_PATTERNS = listOf(
    Regex("""(?i)\bS(?:e(?:ason)?)?[\s._-]*(\d{1,2})[\s._-]*E(?:p(?:isode)?)?[\s._-]*(\d{1,3})\b"""),
    Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b"""),
)

/** Finds an SxxExx / 1x02 marker in a subtitle or release filename. */
internal fun parseSeasonEpisode(fileName: String): Pair<Int, Int>? {
    if (fileName.isBlank()) return null
    for (pattern in SUBTITLE_EPISODE_PATTERNS) {
        val match = pattern.find(fileName) ?: continue
        val season = match.groupValues[1].toIntOrNull() ?: continue
        val episode = match.groupValues[2].toIntOrNull() ?: continue
        return season to episode
    }
    return null
}

/**
 * A tiny loopback HTTP server for extracted subtitles.
 *
 * OkHttpDataSource only understands http(s), so the extracted SRT/VTT is kept
 * in memory under a random token and exposed as
 * http://127.0.0.1:<ephemeral>/sub/<token>.srt. It listens on loopback only,
 * has a small TTL and a strict memory cap.
 */
private object SubtitleProxy {
    private const val MAX_ITEMS = 32
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private const val ITEM_TTL_MS = 2 * 60 * 60 * 1000L

    private data class Item(val body: ByteArray, val extension: String, val at: Long)

    private val items = ConcurrentHashMap<String, Item>()
    private val lock = Any()

    @Volatile
    private var server: ServerSocket? = null

    suspend fun serve(body: ByteArray, extension: String): String? {
        if (body.isEmpty()) return null
        val safeExtension = when {
            extension.equals("vtt", ignoreCase = true) -> "vtt"
            extension.equals("m3u8", ignoreCase = true) -> "m3u8"
            else -> "srt"
        }
        val socket = ensureServer() ?: return null

        cleanup()
        val token = UUID.randomUUID().toString().replace("-", "")
        items[token] = Item(body, safeExtension, System.currentTimeMillis())
        trim()
        return "http://127.0.0.1:${socket.localPort}/sub/$token.$safeExtension"
    }

    private suspend fun ensureServer(): ServerSocket? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = server
            if (current != null && !current.isClosed) return@synchronized current

            val created = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
                }
            }.getOrNull() ?: return@synchronized null

            server = created
            Thread({
                while (true) {
                    val client = runCatching { created.accept() }.getOrNull()
                    if (client == null) {
                        if (created.isClosed) break
                        runCatching { Thread.sleep(100) }
                        continue
                    }
                    Thread({ handle(client) }, "TurkMS-Subtitle").apply {
                        isDaemon = true
                    }.start()
                }
            }, "TurkMS-SubtitleProxy").apply {
                isDaemon = true
            }.start()
            created
        }
    }

    private fun handle(client: Socket) {
        runCatching {
            client.use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                var headerLines = 0
                while (headerLines++ < 100) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val path = requestLine.split(' ').getOrNull(1) ?: "/"
                val file = path.substringAfter("/sub/", "")
                val token = file.substringBefore('.')
                val item = items[token]
                val output = socket.getOutputStream()

                if (item == null || System.currentTimeMillis() - item.at > ITEM_TTL_MS) {
                    if (token.isNotEmpty()) items.remove(token)
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    output.flush()
                    return
                }

                val mime = when (item.extension) {
                    "vtt" -> "text/vtt; charset=utf-8"
                    "m3u8" -> "application/vnd.apple.mpegurl"
                    else -> "application/x-subrip; charset=utf-8"
                }
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${item.body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.US_ASCII))
                output.write(item.body)
                output.flush()
            }
        }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        for (key in items.keys) {
            val item = items[key] ?: continue
            if (now - item.at > ITEM_TTL_MS) items.remove(key)
        }
    }

    private fun trim() {
        var total = items.values.sumOf { it.body.size }
        if (items.size <= MAX_ITEMS && total <= MAX_TOTAL_BYTES) return
        for (entry in items.entries.sortedBy { it.value.at }) {
            if (items.size <= MAX_ITEMS && total <= MAX_TOTAL_BYTES) break
            items.remove(entry.key)
            total -= entry.value.body.size
        }
    }
}

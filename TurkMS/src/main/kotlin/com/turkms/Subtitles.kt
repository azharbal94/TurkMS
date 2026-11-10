package com.turkms

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.Locale

/**
 * Arabic subtitle aggregation.
 *
 * Providers, in order of appearance in the player menu:
 *
 * 1. SubtitleCat - keyless, direct UTF-8 .srt links, title based.
 * 2. SubF2M - keyless, Arabic-only listings and ZIP downloads (resolved by
 *    SubtitleResolver). This is the main source for Turkish series.
 * 3. Wyzie Subs - free API key, `source=all`, plain SRT/VTT links.
 * 4. OpenSubtitles v3 Stremio addon - keyless fallback.
 * 5. SubDL - free API key, ZIP downloads (resolved by SubtitleResolver).
 * 6. SubSource - free API key, ZIP downloads (resolved by SubtitleResolver).
 *
 * All providers are queried in parallel and every result is matched to the
 * requested movie or exact season/episode before it is shown.
 */
object ArabicSubtitles {
    private const val STREMIO_BASE = "https://opensubtitles-v3.strem.io/subtitles"
    private const val WYZIE_BASE = "https://sub.wyzie.io"
    private const val SUBTITLECAT_BASE = "https://www.subtitlecat.com"
    private const val SUBF2M_BASE = "https://subf2m.co"
    private const val SUBDL_API = "https://api.subdl.com/api/v1/subtitles"
    private const val SUBSOURCE_API = "https://api.subsource.net/api/v1"

    // SubtitleResolver converts ASS/SSA to SRT, so those formats are usable too.
    private val PLAYABLE_FORMATS = setOf("srt", "vtt", "ass", "ssa", "")
    private data class SubsourceCandidate(
        val id: Int,
        val release: String,
        val exact: Boolean,
        val unmarked: Boolean,
    )
    private const val MAX_PER_SOURCE = 3
    private const val MAX_TOTAL = 8
    private const val MAX_ITEMS = 400
    private const val PROVIDER_TIMEOUT_MS = 16_000L
    private const val RESOLVE_TIMEOUT_MS = 10_000L

    suspend fun find(
        imdbId: String?,
        title: String? = null,
        originalTitle: String? = null,
        year: Int? = null,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleFile> {
        if (!TurkMSSettings.arabicSubtitles) return emptyList()
        val id = normalizeImdb(imdbId)
        val hasTitle = !title.isNullOrBlank() || !originalTitle.isNullOrBlank()
        if (id == null && !hasTitle) return emptyList()
        if (isTv && (season == null || episode == null)) return emptyList()

        val calls = mutableListOf<suspend () -> List<SubtitleFile>>()
        calls += {
            findSubtitleCat(title, originalTitle, year, isTv, season, episode)
        }
        calls += {
            findSubf2m(title, originalTitle, year, isTv, season, episode)
        }
        if (id != null) {
            calls += { findWyzie(id, isTv, season, episode) }
            calls += { findStremio(id, isTv, season, episode) }
        }
        if (TurkMSSettings.subdlApiKey.isNotBlank()) {
            calls += {
                findSubdl(id, title, originalTitle, year, isTv, season, episode)
            }
        }
        if (TurkMSSettings.subsourceApiKey.isNotBlank()) {
            calls += {
                findSubsource(id, title, originalTitle, year, isTv, season, episode)
            }
        }

        // Parallel lookups keep the subtitle menu responsive when one provider
        // is slow or rate-limited. A slow provider cannot hold the others back
        // for longer than PROVIDER_TIMEOUT_MS.
        val providerResults = coroutineScope {
            calls.map { call ->
                async {
                    runCatching {
                        withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { call() } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val used = mutableSetOf<String>()
        val result = mutableListOf<SubtitleFile>()
        for (subtitle in providerResults.flatten()) {
            if (result.size >= MAX_TOTAL) break
            if (used.add(subtitle.url)) result.add(subtitle)
        }
        return result
    }

    /**
     * SubtitleCat exposes Arabic .srt files directly, so no API key and no
     * archive handling are needed. Results are filtered by the requested year
     * and exact season/episode marker.
     */
    private suspend fun findSubtitleCat(
        title: String?,
        originalTitle: String?,
        year: Int?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val names = listOfNotNull(title, originalTitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (names.isEmpty()) return emptyList()

        val queries = buildList {
            for (name in names) {
                if (isTv) {
                    add("$name S${season}E${episode}")
                    add("$name ${season}x${episode}")
                    // Title-only search is a useful fallback when the site's
                    // episode marker formatting differs from the query.
                    add(name)
                } else {
                    add(listOfNotNull(name, year?.toString()).joinToString(" "))
                    add(name)
                }
            }
        }.distinct().take(4)

        val cacheKey = "subtitlecat:${normalizeTitle(title ?: originalTitle ?: "")}:" +
            "${year ?: 0}:${season ?: 0}:${episode ?: 0}"
        TurkMSCache.get<List<SubtitleFile>>(cacheKey)?.let { return it }

        val candidates = mutableListOf<String>()
        for (query in queries) {
            val body = runCatching {
                app.get(
                    "$SUBTITLECAT_BASE/index.php?search=${encode(query)}",
                    headers = TURKMS_HEADERS + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    timeout = TurkMSSettings.requestTimeout,
                ).text
            }.getOrNull() ?: continue

            val document = runCatching { Jsoup.parse(body) }.getOrNull() ?: continue
            for (anchor in document.select("a[href*=subs/]")) {
                var href = anchor.attr("href").trim()
                if (!href.endsWith(".html", ignoreCase = true)) continue
                href = href
                    .removePrefix("/")
                    .removePrefix("https://www.subtitlecat.com/")
                    .removePrefix("http://www.subtitlecat.com/")
                if (href.isBlank() || href.startsWith("http")) continue
                if (candidates.size < 30 && href !in candidates) candidates.add(href)
            }
        }

        val ranked = candidates.mapNotNull { href ->
            val fileName = decodeUrl(href.substringAfterLast('/').removeSuffix(".html"))
                .replace('.', ' ')
                .replace('_', ' ')
            val marked = parseSeasonEpisode(fileName)
            if (isTv) {
                // A filename without SxxExx is too ambiguous and can easily
                // show the wrong episode, so only exact markers are accepted.
                if (marked == null || marked != (season to episode)) return@mapNotNull null
            } else {
                val candidateYear = parseYear(fileName)
                if (year != null && candidateYear != null && candidateYear != year) {
                    return@mapNotNull null
                }
                val score = maxOf(
                    titleScore(fileName, title ?: ""),
                    originalTitle?.let { titleScore(fileName, it) } ?: 0,
                )
                if (score < 30) return@mapNotNull null
            }
            href
        }.distinct().take(3)

        val result = mutableListOf<SubtitleFile>()
        for (href in ranked) {
            val detailUrl = absoluteUrl("$SUBTITLECAT_BASE/", href)
            val body = runCatching {
                app.get(
                    detailUrl,
                    headers = TURKMS_HEADERS + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    timeout = TurkMSSettings.requestTimeout,
                ).text
            }.getOrNull() ?: continue
            val document = runCatching { Jsoup.parse(body) }.getOrNull() ?: continue

            val download = document.selectFirst("a#download_ar")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: document.select("a[href*='-ar.srt']").firstOrNull()?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                ?: continue
            val downloadUrl = absoluteUrl(detailUrl, download)
            if (downloadUrl.isBlank()) continue

            result.add(
                newSubtitleFile(lang = "Arabic", url = downloadUrl) {
                    headers = mapOf("Referer" to "$SUBTITLECAT_BASE/")
                }
            )
            if (result.size >= MAX_PER_SOURCE) break
        }
        if (result.isNotEmpty()) TurkMSCache.put(cacheKey, result)
        return result
    }

    /**
     * SubF2M is a keyless subtitle archive with a dedicated Arabic listing for
     * each title/season. Its search endpoint resolves titles (not episodes), so
     * a series is matched to its season page first and the item list is then
     * filtered by the SxxExx marker in the release filename.
     *
     * Turkish series are often numbered continuously on SubF2M (for example
     * `S05.EP164` for the first episode of season 5), while TMDB uses
     * season-relative numbers. Both variants are accepted, and the ZIP is
     * resolved with the marker that matches the chosen file.
     */
    private suspend fun findSubf2m(
        title: String?,
        originalTitle: String?,
        year: Int?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val names = listOfNotNull(title, originalTitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (names.isEmpty()) return emptyList()

        val cacheKey = "subf2m:${normalizeTitle(names.first())}:" +
            "${year ?: 0}:${season ?: 0}:${episode ?: 0}"
        TurkMSCache.get<List<SubtitleFile>>(cacheKey)?.let { return it }

        data class Candidate(val url: String, val text: String, val score: Int)

        val candidates = mutableListOf<Candidate>()
        for (name in names.take(2)) {
            val body = runCatching {
                app.get(
                    "$SUBF2M_BASE/subtitles/searchbytitle",
                    params = mapOf("query" to name),
                    headers = TURKMS_HEADERS + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    timeout = TurkMSSettings.requestTimeout,
                ).text
            }.getOrNull() ?: continue

            val document = runCatching { Jsoup.parse(body) }.getOrNull() ?: continue
            for (item in document.select("li")) {
                val anchor = item.selectFirst("div.title a[href]") ?: continue
                val href = anchor.attr("href").trim()
                if (!href.startsWith("/subtitles/")) continue
                val text = item.selectFirst("div.title")?.text()?.trim().orEmpty()
                if (text.isBlank()) continue

                if (isTv) {
                    if (subf2mSeason(text, href) != season) continue
                } else if (year != null) {
                    val candidateYear = parseYear(text)
                    if (candidateYear != null && candidateYear != year) continue
                }

                val score = maxOf(
                    titleScore(text, title ?: ""),
                    originalTitle?.let { titleScore(text, it) } ?: 0,
                )
                if (score < 40) continue
                candidates.add(
                    Candidate(absoluteUrl("$SUBF2M_BASE/", href), text, score)
                )
            }
        }

        val ranked = candidates
            .distinctBy { it.url }
            .sortedByDescending { it.score }
            .take(3)
        if (ranked.isEmpty()) return emptyList()

        data class Item(
            val fileName: String,
            val pageUrl: String,
            val marked: Pair<Int, Int>?,
        )

        val items = mutableListOf<Item>()
        for (candidate in ranked) {
            val pageUrl = "${candidate.url.trimEnd('/')}/arabic"
            val seasonHint = subf2mSeason(candidate.text, candidate.url)
            val body = runCatching {
                app.get(
                    pageUrl,
                    headers = TURKMS_HEADERS + mapOf(
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    ),
                    timeout = TurkMSSettings.requestTimeout,
                ).text
            }.getOrNull() ?: continue

            val document = runCatching { Jsoup.parse(body) }.getOrNull() ?: continue
            for (row in document.select("li.item")) {
                val language = row.selectFirst("span.language")?.text()?.trim()
                if (!isArabic(language)) continue
                val download = row.selectFirst("a.download[href]") ?: continue
                val href = download.attr("href").trim()
                if (href.isBlank()) continue
                val fileName = row.select("ul.scrolllist li")
                    .joinToString(" ") { it.text().trim() }
                    .ifBlank { row.text().replace(Regex("\\s+"), " ").trim() }
                val itemUrl = absoluteUrl(pageUrl, href)
                if (itemUrl.isBlank()) continue
                if (items.none { it.pageUrl == itemUrl }) {
                    items.add(
                        Item(
                            fileName = fileName,
                            pageUrl = itemUrl,
                            marked = subf2mItemMarker(fileName, seasonHint),
                        )
                    )
                }
                if (items.size >= MAX_ITEMS) break
            }
            if (items.size >= MAX_ITEMS) break
        }
        if (items.isEmpty()) return emptyList()

        val selected: List<Pair<Item, Int?>> = if (isTv && season != null && episode != null) {
            // SubF2M may number a series continuously. The lowest episode
            // marker on the season page reveals the offset (0 when the site
            // already uses season-relative numbers).
            val seasonEpisodes = items.mapNotNull { it.marked }
                .filter { it.first == season }
                .map { it.second }
            val offset = ((seasonEpisodes.minOrNull() ?: 1) - 1).coerceAtLeast(0)
            val cumulativeTarget = offset + episode
            val targets = setOf(episode, cumulativeTarget)

            val matched = items.mapNotNull { item ->
                val marked = item.marked ?: return@mapNotNull null
                if (marked.first != season) return@mapNotNull null
                if (marked.second !in targets) return@mapNotNull null
                // Prefer the cumulative marker: it matches the file that
                // SubtitleResolver will pick out of a season pack.
                val target = if (marked.second == cumulativeTarget) cumulativeTarget else episode
                item to target
            }
            // Untagged files are only used when nothing tagged matched.
            matched.ifEmpty { items.filter { it.marked == null }.map { it to null } }
        } else {
            items.map { it to null }
        }

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for ((item, target) in selected) {
            if (result.size >= MAX_PER_SOURCE) break
            val downloadUrl = if (item.pageUrl.endsWith("/download")) {
                item.pageUrl
            } else {
                "${item.pageUrl.trimEnd('/')}/download"
            }
            if (!seen.add(downloadUrl)) continue

            val downloadHeaders = mapOf(
                "Referer" to item.pageUrl,
                "User-Agent" to TURKMS_UA,
            )
            val resolved = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    SubtitleResolver.resolve(
                        downloadUrl,
                        headers = downloadHeaders,
                        season = if (isTv && target != null) season else null,
                        episode = if (isTv) target else null,
                    )
                }
            }.getOrNull()
                ?: runCatching {
                    withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                        SubtitleResolver.resolve(downloadUrl, headers = downloadHeaders)
                    }
                }.getOrNull()
                ?: continue

            result.add(
                newSubtitleFile(lang = "Arabic", url = resolved.url) {
                    headers = resolved.headers
                }
            )
        }
        if (result.isNotEmpty()) TurkMSCache.put(cacheKey, result)
        return result
    }

    /**
     * Finds the season/episode marker for one SubF2M file. Turkish releases are
     * often named `Kurulus.Osman.164.Bolum...` without an `SxxExx` prefix, so
     * the bare episode number is used with the season of the listing page.
     */
    private fun subf2mItemMarker(fileName: String, seasonHint: Int?): Pair<Int, Int>? {
        parseSeasonEpisode(fileName)?.let { return it }
        val bare = Regex(
            """(?i)\b(\d{1,4})[.\s_-]*(?:b(?:ö|o)l(?:ü|u)m|episode|ep)\b""",
        ).find(fileName)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        val season = seasonHint ?: return null
        return season to bare
    }

    /** Reads the season number from a SubF2M search result title or slug. */
    private fun subf2mSeason(text: String, href: String): Int? {
        Regex("""(?i)\bseason\s*(\d{1,2})\b""").find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""(?i)season[-_](\d{1,2})(?:[-_]|$)""").find(href)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val ordinals = mapOf(
            "first" to 1, "second" to 2, "third" to 3, "fourth" to 4,
            "firth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
            "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11,
            "twelfth" to 12,
        )
        val lowerText = text.lowercase(Locale.US)
        val lowerHref = href.lowercase(Locale.US)
        for ((word, number) in ordinals) {
            if (lowerText.contains("$word season") || lowerHref.contains("-$word-season")) {
                return number
            }
        }
        return null
    }

    private suspend fun findWyzie(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val key = TurkMSSettings.wyzieApiKey.trim()
        if (key.isBlank()) return emptyList()

        val url = buildString {
            append(WYZIE_BASE)
            append("/search?id=")
            append(encode(id))
            // `all` asks Wyzie to aggregate every source the key can access,
            // instead of its small default source.
            append("&source=all&language=ar&format=srt,ass&encoding=utf-8&limit=10&key=")
            append(encode(key))
            if (isTv) {
                append("&season=")
                append(season)
                append("&episode=")
                append(episode)
            }
        }

        val body = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to TURKMS_UA,
                ),
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val items = runCatching {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("data") ?: root.optJSONArray("subtitles")
                }

                else -> null
            }
        }.getOrNull() ?: return emptyList()

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for (i in 0 until items.length()) {
            if (result.size >= MAX_PER_SOURCE) break
            val item = items.optJSONObject(i) ?: continue
            if (!isArabic(item.optString("language"))) continue
            if (item.optString("format").lowercase(Locale.US) !in PLAYABLE_FORMATS) continue
            val subUrl = item.optString("url")
            if (subUrl.isBlank() || !seen.add(subUrl)) continue
            if (isTv) {
                val marked = parseSeasonEpisode(item.optString("fileName"))
                if (marked != null && marked != (season to episode)) continue
            }

            val resolved = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    SubtitleResolver.resolve(subUrl, season = season, episode = episode)
                }
            }.getOrNull() ?: continue
            result.add(
                newSubtitleFile(lang = "Arabic", url = resolved.url) {
                    headers = resolved.headers
                }
            )
        }
        return result
    }

    private suspend fun findStremio(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val url = if (isTv) {
            "$STREMIO_BASE/series/$id:$season:$episode.json"
        } else {
            "$STREMIO_BASE/movie/$id.json"
        }

        val body = runCatching {
            app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to TURKMS_UA,
                ),
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val subtitles = runCatching {
            JSONObject(body).optJSONArray("subtitles")
        }.getOrNull() ?: return emptyList()

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for (i in 0 until subtitles.length()) {
            if (result.size >= MAX_PER_SOURCE) break
            val item = subtitles.optJSONObject(i) ?: continue
            if (!isArabic(item.optString("lang"))) continue
            val subUrl = item.optString("url")
            if (subUrl.isBlank() || !seen.add(subUrl)) continue

            // The addon sometimes tags every season with the requested season
            // number. Filenames such as "Se02.Ep001" reveal the real episode,
            // so drop an obvious mismatch instead of serving wrong subtitles.
            if (isTv) {
                val marked = parseSeasonEpisode(item.optString("subtitleFileName"))
                if (marked != null && marked != (season to episode)) continue
            }

            val resolved = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    SubtitleResolver.resolve(subUrl, season = season, episode = episode)
                }
            }.getOrNull() ?: continue
            result.add(
                newSubtitleFile(lang = "Arabic", url = resolved.url) {
                    headers = resolved.headers
                }
            )
        }
        return result
    }

    /**
     * SubDL API: search by IMDb id (or title), request Arabic, and prefer the
     * individual files that `unpack=1` exposes for season packs. When no
     * unpacked file matches, the season/movie ZIP is resolved locally.
     */
    private suspend fun findSubdl(
        id: String?,
        title: String?,
        originalTitle: String?,
        year: Int?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val key = TurkMSSettings.subdlApiKey.trim()
        if (key.isBlank()) return emptyList()

        val params = buildMap {
            put("api_key", key)
            put("languages", "AR")
            put("subs_per_page", "30")
            put("unpack", "1")
            if (id != null) {
                put("imdb_id", id)
            } else {
                val query = title ?: originalTitle ?: return emptyList()
                put("film_name", query)
                if (year != null) put("year", year.toString())
            }
            if (isTv) {
                put("type", "tv")
                season?.let { put("season_number", it.toString()) }
                episode?.let { put("episode_number", it.toString()) }
            } else {
                put("type", "movie")
            }
        }

        val body = runCatching {
            app.get(
                SUBDL_API,
                params = params,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to TURKMS_UA,
                ),
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (root.has("status") && !root.optBoolean("status")) return emptyList()
        val subtitles = root.optJSONArray("subtitles") ?: return emptyList()

        val result = mutableListOf<SubtitleFile>()
        val seen = mutableSetOf<String>()
        for (i in 0 until subtitles.length()) {
            if (result.size >= MAX_PER_SOURCE) break
            val item = subtitles.optJSONObject(i) ?: continue
            val language = item.optString("language").ifBlank { item.optString("lang") }
            if (!isArabic(language)) continue

            var downloadUrl = subdlUrl(item.optString("url"))
            if (downloadUrl.isBlank()) continue

            val unpacked = item.optJSONArray("unpack_files")
            if (unpacked != null) {
                for (j in 0 until unpacked.length()) {
                    val file = unpacked.optJSONObject(j) ?: continue
                    val fileLanguage = file.optString("language").ifBlank { language }
                    if (!isArabic(fileLanguage)) continue
                    if (isTv) {
                        val fileEpisode = file.optInt("episode", -1)
                        if (fileEpisode != episode) continue
                        val fileSeason = file.optInt("season", -1)
                        if (fileSeason > 0 && season != null && fileSeason != season) continue
                    }
                    val fileUrl = subdlUrl(file.optString("url"))
                    if (fileUrl.isNotBlank()) {
                        downloadUrl = fileUrl
                        break
                    }
                }
            }

            if (!seen.add(downloadUrl)) continue
            val resolved = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    SubtitleResolver.resolve(downloadUrl, season = season, episode = episode)
                }
            }.getOrNull() ?: continue
            result.add(
                newSubtitleFile(lang = "Arabic", url = resolved.url) {
                    headers = resolved.headers
                }
            )
        }
        return result
    }

    /**
     * SubSource API: resolve the movie/series id, list Arabic subtitles, and
     * download the selected entry. Downloads are ZIP files, handled locally.
     */
    private suspend fun findSubsource(
        id: String?,
        title: String?,
        originalTitle: String?,
        year: Int?,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<SubtitleFile> {
        val key = TurkMSSettings.subsourceApiKey.trim()
        if (key.isBlank()) return emptyList()

        val searchParams = buildMap {
            if (id != null) {
                put("searchType", "imdb")
                put("imdb", id)
            } else {
                val query = title ?: originalTitle ?: return emptyList()
                put("searchType", "text")
                put("q", query)
            }
            if (isTv && season != null) put("season", season.toString())
        }
        val authHeaders = mapOf(
            "X-API-Key" to key,
            "Accept" to "application/json",
            "User-Agent" to TURKMS_UA,
        )

        val searchBody = runCatching {
            app.get(
                "$SUBSOURCE_API/movies/search",
                params = searchParams,
                headers = authHeaders,
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val searchJson = runCatching { JSONObject(searchBody) }.getOrNull() ?: return emptyList()
        if (!searchJson.optBoolean("success", true)) return emptyList()
        val movies = searchJson.optJSONArray("data") ?: return emptyList()
        val movie = (0 until movies.length())
            .mapNotNull { movies.optJSONObject(it) }
            .firstOrNull() ?: return emptyList()
        val movieId = movie.optInt("movieId", movie.optInt("id", -1))
        if (movieId <= 0) return emptyList()

        val listBody = runCatching {
            app.get(
                "$SUBSOURCE_API/subtitles",
                params = mapOf(
                    "movieId" to movieId.toString(),
                    "limit" to "100",
                    "sort" to "popular",
                ),
                headers = authHeaders,
                timeout = TurkMSSettings.requestTimeout,
            ).text
        }.getOrNull() ?: return emptyList()

        val listJson = runCatching { JSONObject(listBody) }.getOrNull() ?: return emptyList()
        if (!listJson.optBoolean("success", true)) return emptyList()
        val entries = listJson.optJSONArray("data") ?: return emptyList()

        val candidates = mutableListOf<SubsourceCandidate>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (!isArabic(entry.optString("language"))) continue
            val subtitleId = entry.optInt("subtitleId", entry.optInt("id", -1))
            if (subtitleId <= 0) continue

            val releaseInfo = entry.optJSONArray("releaseInfo")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { v -> v.isNotBlank() } }
                    .joinToString(" ")
            } ?: entry.optString("releaseInfo")
            val release = listOf(releaseInfo, entry.optString("releaseType"))
                .filter { it.isNotBlank() }
                .joinToString(" ")

            val marked = parseSeasonEpisode(release)
            if (isTv) {
                if (marked != null && marked != (season to episode)) continue
                candidates.add(
                    SubsourceCandidate(
                        id = subtitleId,
                        release = release,
                        exact = marked == (season to episode),
                        unmarked = marked == null,
                    )
                )
            } else {
                candidates.add(
                    SubsourceCandidate(
                        id = subtitleId,
                        release = release,
                        exact = false,
                        unmarked = true,
                    )
                )
            }
        }

        val selected = if (isTv) {
            val exact = candidates.filter { it.exact }
            if (exact.isNotEmpty()) {
                exact
            } else {
                // No episode marker at all is safer than a marker for a
                // different episode. Wrong-episode matches are discarded.
                candidates.filter { it.unmarked }
            }
        } else {
            candidates
        }.take(MAX_PER_SOURCE)

        val result = mutableListOf<SubtitleFile>()
        for (candidate in selected) {
            val downloadUrl = "$SUBSOURCE_API/subtitles/${candidate.id}/download?api_key=${encode(key)}"
            val resolved = runCatching {
                withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                    SubtitleResolver.resolve(downloadUrl, season = season, episode = episode)
                }
            }.getOrNull() ?: continue
            result.add(
                newSubtitleFile(lang = "Arabic", url = resolved.url) {
                    headers = resolved.headers
                }
            )
        }
        return result
    }

    private fun subdlUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val full = when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "https://dl.subdl.com$value"
            else -> "https://dl.subdl.com/$value"
        }
        // The API key belongs to api.subdl.com, not dl.subdl.com.
        return Regex("""([?&])api_key=[^&]*""")
            .replace(full) { it.groupValues[1] }
            .replace("?&", "?")
            .trimEnd('?', '&')
    }

    private fun isArabic(value: String?): Boolean {
        val language = value?.trim()?.lowercase(Locale.US) ?: return false
        return language == "ar" ||
            language == "ara" ||
            language == "arab" ||
            language == "arabic" ||
            language.startsWith("ar-") ||
            language.contains("arab")
    }

    private fun decodeUrl(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    /** Accepts `tt123`, `123`, or a TMDB/IMDb id string. */
    fun normalizeImdb(imdbId: String?): String? {
        val raw = imdbId?.trim()?.removePrefix("tt") ?: return null
        if (raw.isBlank() || raw.any { !it.isDigit() }) return null
        return "tt$raw"
    }
}

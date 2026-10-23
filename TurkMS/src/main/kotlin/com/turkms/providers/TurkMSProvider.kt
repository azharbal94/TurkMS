package com.turkms.providers

import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.turkms.AggRequest
import com.turkms.ArabicSubtitles
import com.turkms.SiteResult
import com.turkms.SiteSource
import com.turkms.SiteEpisodeData
import com.turkms.TmdbClient
import com.turkms.TmdbItem
import com.turkms.TurkMSCache
import com.turkms.TurkMSSettings
import com.turkms.buildAggData
import com.turkms.buildSiteEpisodeData
import com.turkms.buildSiteUrl
import com.turkms.normalizeTitle
import com.turkms.parseAggData
import com.turkms.parseSiteEpisodeData
import com.turkms.parseSiteUrl
import com.turkms.parseYear
import com.turkms.sortForPreferredQuality
import com.turkms.sources.CanliDizi14Source
import com.turkms.sources.DdiziSource
import com.turkms.sources.DiziIzleNowSource
import com.turkms.sources.TrDiziIzleSource
import com.turkms.sources.YoutubeMovieSource
import com.turkms.titleScore
import com.turkms.toLoadResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Collections

class TurkMSProvider : MainAPI() {
    private companion object {
        // TMDB returns 20 items per page. Three TMDB pages per app page means
        // every catalog row opens with 60 titles, and load-more keeps going.
        const val TMDB_PAGES_PER_APP_PAGE = 3
        const val MAX_APP_PAGES = 6
        const val TMDB_ENOUGH_RESULTS = 5
        const val MAX_CANDIDATES = 5
        const val SOURCE_TIMEOUT_MS = 22_000L

        // A slow source (Cloudflare challenge, dead CDN) used to hold back every
        // link until the 22s timeout, which the player shows as buffering. Start
        // playback as soon as one source is done and give the others a short
        // grace period to add mirrors.
        const val COLLECT_TIMEOUT_MS = 14_000L
        const val MIRROR_GRACE_MS = 2_500L
    }

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "TurkMS"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Every row is Turkish-only. Movies and series each get their own rows so
    // the home page never mixes in English, Korean, Japanese or other content.
    // The year is read dynamically so changing it in settings immediately
    // updates the row names and filters on the next catalog refresh.
    override val mainPage: List<MainPageData>
        get() {
            val movieYear = selectedMovieYear()
            val seriesYear = selectedSeriesYear()
            return listOf(
                MainPageData(rowName("Turkish Movies", movieYear), "tr_movies"),
                MainPageData(rowName("Turkish Series", seriesYear), "tr_series"),
                MainPageData(rowName("New Turkish Movies", movieYear), "tr_movies_new"),
                MainPageData(rowName("New Turkish Series", seriesYear), "tr_series_new"),
                MainPageData(rowName("Top Rated Turkish Movies", movieYear), "tr_movies_top"),
                MainPageData(rowName("Top Rated Turkish Series", seriesYear), "tr_series_top"),
                // The Ddizi site has no TMDB metadata, but its own latest list is a
                // useful, visible discovery row on the main page.
                MainPageData("Ddizi Latest", "ddizi_latest"),
            )
        }

    private fun selectedMovieYear(): Int? =
        TurkMSSettings.movieYear.takeIf { it > 0 }

    private fun selectedSeriesYear(): Int? =
        TurkMSSettings.seriesYear.takeIf { it > 0 }

    private fun rowName(base: String, year: Int?): String =
        if (year == null) "$base (All Years)" else "$base · $year"

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    private fun currentYear(): Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

    /**
     * TMDB date filters for the selected year. `newest` rows also exclude
     * future dates when the selected year is the current one.
     */
    private fun yearParams(year: Int?, isTv: Boolean, newest: Boolean = false): Map<String, String> {
        val dateField = if (isTv) "first_air_date" else "release_date"
        if (year == null) {
            return if (newest) mapOf("$dateField.lte" to today()) else emptyMap()
        }
        val upper = if (newest && year >= currentYear()) today() else "$year-12-31"
        return mapOf(
            "$dateField.gte" to "$year-01-01",
            "$dateField.lte" to upper,
        )
    }

    private fun allSources(): List<SiteSource> = listOf(
        CanliDizi14Source(),
        DdiziSource(),
        DiziIzleNowSource(),
        TrDiziIzleSource(),
        YoutubeMovieSource(),
    )

    private fun enabledSources(): List<SiteSource> = allSources().filter {
        when (it.key) {
            "canlidizi14" -> TurkMSSettings.sourceCanliDizi14
            "ddizi" -> TurkMSSettings.sourceDdizi
            "diziizlenow" -> TurkMSSettings.sourceDiziIzleNow
            "trdiziizle" -> TurkMSSettings.sourceTrDiziIzle
            "youtube" -> TurkMSSettings.sourceYoutube
            else -> false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == "ddizi_latest") {
            if (!TurkMSSettings.sourceDdizi) {
                return newHomePageResponse(request, emptyList(), hasNext = false)
            }
            val items = runCatching {
                withTimeoutOrNull(45_000) { DdiziSource().latest(page) }.orEmpty()
            }.getOrDefault(emptyList())
            val list = items.map { it.toSiteSearchResponse("ddizi") }
            return newHomePageResponse(
                request,
                list,
                // Ddizi's new and archive catalogs are merged and locally
                // paginated by DdiziSource.
                hasNext = list.size >= 10,
            )
        }

        if (page > MAX_APP_PAGES) return null

        val movieYear = selectedMovieYear()
        val seriesYear = selectedSeriesYear()
        val (path, params) = when (request.data) {
            // with_original_language=tr keeps the catalogs Turkish-only, instead of
            // mixing in whatever happens to be popular worldwide. The year
            // filters are applied to every movie/series row.
            "tr_movies" -> "/discover/movie" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "popularity.desc",
                ) + yearParams(movieYear, isTv = false)
            )

            "tr_series" -> "/discover/tv" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "popularity.desc",
                ) + yearParams(seriesYear, isTv = true)
            )

            "tr_movies_new" -> "/discover/movie" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "primary_release_date.desc",
                    "vote_count.gte" to "5",
                ) + yearParams(movieYear, isTv = false, newest = true)
            )

            "tr_series_new" -> "/discover/tv" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "first_air_date.desc",
                    "vote_count.gte" to "5",
                ) + yearParams(seriesYear, isTv = true, newest = true)
            )

            "tr_movies_top" -> "/discover/movie" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "vote_average.desc",
                    "vote_count.gte" to "100",
                ) + yearParams(movieYear, isTv = false)
            )

            "tr_series_top" -> "/discover/tv" to (
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "vote_average.desc",
                    "vote_count.gte" to "100",
                ) + yearParams(seriesYear, isTv = true)
            )

            else -> return null
        }

        val items = fetchTmdbPages(path, params, page)
        val list = items
            .distinctBy { it.id to it.isTv }
            .map { it.toSearchResponse(this) }
        return newHomePageResponse(
            request,
            list,
            hasNext = page < MAX_APP_PAGES,
        )
    }

    /**
     * Fetches several TMDB pages per app page. This is what makes a row show
     * 60 titles immediately instead of the usual 20, while still supporting
     * CloudStream's load-more flow.
     */
    private suspend fun fetchTmdbPages(
        path: String,
        params: Map<String, String>,
        appPage: Int,
    ): List<TmdbItem> = coroutineScope {
        val firstPage = (appPage - 1) * TMDB_PAGES_PER_APP_PAGE + 1
        (firstPage until firstPage + TMDB_PAGES_PER_APP_PAGE).map { tmdbPage ->
            async {
                TmdbClient.list(path, params + ("page" to tmdbPage.toString()))
            }
        }.awaitAll().flatten()
    }

    private fun SiteResult.toSiteSearchResponse(sourceKey: String): SearchResponse {
        val dataUrl = buildSiteUrl(sourceKey, url)
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(name, dataUrl, fix = false) {
                posterUrl = this@toSiteSearchResponse.poster
                year = this@toSiteSearchResponse.year
            }
        } else {
            newMovieSearchResponse(name, dataUrl, fix = false) {
                posterUrl = this@toSiteSearchResponse.poster
                year = this@toSiteSearchResponse.year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val tmdb = TmdbClient.searchMulti(query)
        val fromTmdb = tmdb.map { it.toSearchResponse(this) }
        // TMDB is the primary index, but if it has little to offer we also ask the
        // scraped sites so niche Turkish titles are not lost.
        if (tmdb.size >= TMDB_ENOUGH_RESULTS) return fromTmdb
        return fromTmdb + searchSites(query)
    }

    private suspend fun searchSites(query: String): List<SearchResponse> {
        val hits: List<Pair<SiteSource, SiteResult>> = coroutineScope {
            // Plain search stays on the series sites; YouTube is a movie-playback
            // fallback and would only add noisy results here.
            enabledSources().filter { it.key != "youtube" }.map { source ->
                async {
                    val results = runCatching {
                        withTimeoutOrNull(15_000) {
                            TurkMSCache.getOrPut(
                                "search:${source.key}:${normalizeTitle(query)}",
                            ) { source.search(query) }
                        } ?: emptyList()
                    }.getOrNull() ?: emptyList()
                    results.map { source to it }
                }
            }.awaitAll().flatten()
        }
        val seen = mutableSetOf<String>()
        return hits
            .sortedByDescending { titleScore(it.second.name, query) }
            .filter { seen.add(normalizeTitle(it.second.name)) }
            .take(40)
            .map { (source, result) ->
                val url = buildSiteUrl(source.key, result.url)
                if (result.type == TvType.TvSeries) {
                    newTvSeriesSearchResponse(result.name, url, fix = false) {
                        posterUrl = result.poster
                        year = result.year
                    }
                } else {
                    newMovieSearchResponse(result.name, url, fix = false) {
                        posterUrl = result.poster
                        year = result.year
                    }
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("turkms://site?")) {
            val parts = parseSiteUrl(url) ?: return null
            val source = enabledSources().firstOrNull { it.key == parts.key }
                ?: allSources().firstOrNull { it.key == parts.key }
                ?: return null
            val details = source.details(parts.url) ?: return null
            return details.toLoadResponse(
                this,
                movieDataUrl = buildSiteUrl(source.key, details.url),
                episodeData = { episode ->
                    buildSiteEpisodeData(source.key, episode, details.name, details.year)
                },
            )
        }

        if (url.startsWith("turkms://movie/")) {
            val id = url.substringAfterLast("/").toIntOrNull() ?: return null
            return loadMovie(id)
        }

        if (url.startsWith("turkms://tv/")) {
            val id = url.substringAfterLast("/").toIntOrNull() ?: return null
            return loadTv(id)
        }

        return null
    }

    private suspend fun loadMovie(id: Int): LoadResponse? {
        val json = TmdbClient.movie(id) ?: return null
        val title = json.optString("title").ifBlank { json.optString("original_title") }
        if (title.isBlank()) return null
        val original = json.optString("original_title").takeIf { it.isNotBlank() && it != title }
        val year = parseYear(json.optString("release_date"))
        val imdb = TmdbClient.imdbId(json)
        val data = buildAggData(title, original, year, null, null, imdbId = imdb)
        val scoreValue = json.optDouble("vote_average").takeUnless { it.isNaN() || it == 0.0 }

        return newMovieLoadResponse(title, "turkms://movie/$id", TvType.Movie, dataUrl = data) {
            posterUrl = TmdbClient.poster(json.optString("poster_path"))
            backgroundPosterUrl = TmdbClient.backdrop(json.optString("backdrop_path"))
            plot = json.optString("overview").takeIf { it.isNotBlank() }
            this.year = year
            duration = json.optInt("runtime").takeIf { it > 0 }
            tags = TmdbClient.genres(json)
            actors = TmdbClient.actors(json)
            score = scoreValue?.let { Score.from10(it) }
            trailers = TmdbClient.trailers(json)
            recommendations = TmdbClient.recommendations(json).map { it.toSearchResponse(this@TurkMSProvider) }
            TmdbClient.imdbId(json)?.let { syncData["IMDB"] = it }
        }
    }

    private suspend fun loadTv(id: Int): LoadResponse? {
        val json = TmdbClient.tv(id) ?: return null
        val title = json.optString("name").ifBlank { json.optString("original_name") }
        if (title.isBlank()) return null
        val original = json.optString("original_name").takeIf { it.isNotBlank() && it != title }
        val year = parseYear(json.optString("first_air_date"))
        val scoreValue = json.optDouble("vote_average").takeUnless { it.isNaN() || it == 0.0 }
        val showPoster = TmdbClient.poster(json.optString("poster_path"))
        val imdb = TmdbClient.imdbId(json)

        val seasons = mutableListOf<Int>()
        val episodeCounts = mutableMapOf<Int, Int>()
        val seasonsArray = json.optJSONArray("seasons")
        if (seasonsArray != null) {
            for (i in 0 until seasonsArray.length()) {
                val season = seasonsArray.optJSONObject(i) ?: continue
                val number = season.optInt("season_number")
                val count = season.optInt("episode_count")
                if (number > 0 && count > 0) {
                    seasons.add(number)
                    episodeCounts[number] = count
                }
            }
        }

        val seasonData = coroutineScope {
            seasons.map { season ->
                async {
                    season to runCatching { TmdbClient.season(id, season) }.getOrNull()
                }
            }.awaitAll()
        }

        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        // Turkish sites often number episodes continuously across seasons, so we
        // also keep the running episode number for playback matching.
        var episodesBeforeSeason = 0
        for ((seasonNumber, seasonJson) in seasonData) {
            val array = seasonJson?.optJSONArray("episodes")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val ep = array.optJSONObject(i) ?: continue
                    val number = ep.optInt("episode_number")
                    if (number <= 0) continue
                    val epName = ep.optString("name").takeIf { it.isNotBlank() }
                        ?: "S${seasonNumber}E$number"
                    val epData = buildAggData(
                        title = title,
                        originalTitle = original,
                        year = year,
                        season = seasonNumber,
                        episode = number,
                        absoluteEpisode = episodesBeforeSeason + number,
                        imdbId = imdb,
                    )
                    episodes.add(
                        newEpisode(epData, initializer = {
                            name = epName
                            season = seasonNumber
                            episode = number
                            posterUrl = TmdbClient.poster(ep.optString("still_path")) ?: showPoster
                            description = ep.optString("overview").takeIf { it.isNotBlank() }
                        }, fix = false)
                    )
                }
            }
            episodesBeforeSeason += episodeCounts[seasonNumber] ?: 0
        }

        return newTvSeriesLoadResponse(title, "turkms://tv/$id", TvType.TvSeries, episodes) {
            posterUrl = showPoster
            backgroundPosterUrl = TmdbClient.backdrop(json.optString("backdrop_path"))
            plot = json.optString("overview").takeIf { it.isNotBlank() }
            this.year = year
            tags = TmdbClient.genres(json)
            actors = TmdbClient.actors(json)
            score = scoreValue?.let { Score.from10(it) }
            trailers = TmdbClient.trailers(json)
            recommendations = TmdbClient.recommendations(json).map { it.toSearchResponse(this@TurkMSProvider) }
            TmdbClient.imdbId(json)?.let { syncData["IMDB"] = it }
        }
    }

    private suspend fun addSiteSubtitles(
        meta: SiteEpisodeData,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!TurkMSSettings.arabicSubtitles) return
        runCatching {
            val imdb = withTimeoutOrNull(10_000) {
                TmdbClient.resolveImdb(
                    title = meta.title,
                    originalTitle = null,
                    year = meta.year,
                    isTv = true,
                )
            }
            ArabicSubtitles.find(
                imdbId = imdb,
                title = meta.title,
                year = meta.year,
                isTv = true,
                season = meta.season,
                episode = meta.episode,
            ).forEach(subtitleCallback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.startsWith("turkms://site-ep?")) {
            val meta = parseSiteEpisodeData(data) ?: return false
            val source = enabledSources().firstOrNull { it.key == meta.key }
                ?: allSources().firstOrNull { it.key == meta.key }
                ?: return false
            var found = false
            source.loadLinks(meta.url) {
                found = true
                callback(it)
            }
            addSiteSubtitles(meta, subtitleCallback)
            return found
        }

        if (data.startsWith("turkms://site?")) {
            val parts = parseSiteUrl(data) ?: return false
            val source = enabledSources().firstOrNull { it.key == parts.key }
                ?: allSources().firstOrNull { it.key == parts.key }
                ?: return false
            var found = false
            source.loadLinks(parts.url) {
                found = true
                callback(it)
            }

            if (TurkMSSettings.arabicSubtitles) {
                runCatching {
                    val details = withTimeoutOrNull(10_000) { source.details(parts.url) }
                    if (details != null) {
                        val isTv = details.type == TvType.TvSeries
                        val episode = details.episodes.firstOrNull { it.url == parts.url }
                        val imdb = withTimeoutOrNull(10_000) {
                            TmdbClient.resolveImdb(
                                title = details.name,
                                originalTitle = null,
                                year = details.year,
                                isTv = isTv,
                            )
                        }
                        ArabicSubtitles.find(
                            imdbId = imdb,
                            title = details.name,
                            year = details.year,
                            isTv = isTv,
                            season = episode?.season,
                            episode = episode?.episode,
                        ).forEach { subtitleCallback(it) }
                    }
                }
            }
            return found
        }

        val request = parseAggData(data) ?: return false
        val links = collectLinks(request)
        for (link in links) callback(link)

        if (TurkMSSettings.arabicSubtitles) {
            val imdb = request.imdbId ?: runCatching {
                withTimeoutOrNull(10_000) {
                    TmdbClient.resolveImdb(
                        title = request.title,
                        originalTitle = request.originalTitle,
                        year = request.year,
                        isTv = request.season != null,
                    )
                }
            }.getOrNull()
            ArabicSubtitles.find(
                imdbId = imdb,
                title = request.title,
                originalTitle = request.originalTitle,
                year = request.year,
                isTv = request.season != null,
                season = request.season,
                episode = request.episode,
            ).forEach { subtitleCallback(it) }
        }
        return links.isNotEmpty()
    }

    private suspend fun collectLinks(request: AggRequest): List<ExtractorLink> {
        val sources = enabledSources()
        if (sources.isEmpty()) return emptyList()

        val results = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        coroutineScope {
            val jobs = sources.map { source ->
                launch {
                    val found = try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { collectFromSource(source, request) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                    if (!found.isNullOrEmpty()) results.addAll(found)
                }
            }

            val started = System.currentTimeMillis()
            var firstResultAt = 0L
            while (true) {
                val now = System.currentTimeMillis()
                // Every source finished (or was cancelled) - nothing left to wait for.
                if (jobs.none { it.isActive }) break
                if (results.isNotEmpty()) {
                    if (firstResultAt == 0L) firstResultAt = now
                    if (now - firstResultAt >= MIRROR_GRACE_MS) break
                }
                if (now - started >= COLLECT_TIMEOUT_MS) break
                delay(75)
            }
            jobs.forEach { it.cancel() }
        }

        val snapshot = synchronized(results) { results.toList() }
        val seen = mutableSetOf<String>()
        return snapshot
            .filter { it.url.isNotBlank() && seen.add(it.url) }
            .sortForPreferredQuality()
    }

    private suspend fun collectFromSource(source: SiteSource, request: AggRequest): List<ExtractorLink> {
        // YouTube only carries films we care about; keep it out of episode lookups.
        if (request.season != null && source.key == "youtube") return emptyList()

        val queries = listOfNotNull(request.title, request.originalTitle)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeTitle(it) }

        // Query the Turkish and original title in parallel instead of one after
        // another. Searches are cached, so switching episodes is much faster.
        val perQuery = coroutineScope {
            queries.map { query ->
                async<List<SiteResult>> {
                    runCatching {
                        TurkMSCache.getOrPut(
                            "search:${source.key}:${normalizeTitle(query)}",
                        ) {
                            withTimeoutOrNull(12_000) { source.search(query) } ?: emptyList()
                        }
                    }.getOrNull() ?: emptyList()
                }
            }.awaitAll()
        }

        val candidates = mutableListOf<Pair<SiteResult, Int>>()
        for ((query, results) in queries.zip(perQuery)) {
            for (result in results) {
                var score = maxOf(
                    titleScore(result.name, request.title),
                    request.originalTitle?.let { titleScore(result.name, it) } ?: 0,
                )
                if (request.year != null && result.year == request.year) score += 10
                candidates.add(result to score)
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val links = mutableListOf<ExtractorLink>()
        val deadline = System.currentTimeMillis() + 18_000
        var attempts = 0

        // Search-result types are only a hint (canlidizi14 marks digital series as
        // movies); the detail page decides what a candidate actually is.
        val ranked = candidates
            .filter { it.second >= 50 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.url }
            .take(MAX_CANDIDATES)

        for ((result, _) in ranked) {
            if (attempts >= 3 || System.currentTimeMillis() > deadline) break
            attempts++
            val details = runCatching {
                TurkMSCache.getOrPut("details:${source.key}:${result.url}") {
                    withTimeoutOrNull(12_000) { source.details(result.url) }
                }
            }.getOrNull()

            if (request.season != null && request.episode != null) {
                if (details == null || details.type != TvType.TvSeries || details.episodes.isEmpty()) {
                    continue
                }
                val episode = details.episodes.firstOrNull {
                    it.season == request.season && it.episode == request.episode
                } ?: if (request.season == 1) {
                    // Sites that only expose one season.
                    details.episodes.firstOrNull { it.episode == request.episode }
                } else {
                    // Sites that number episodes continuously across seasons.
                    request.absoluteEpisode?.let { absolute ->
                        details.episodes.firstOrNull { it.episode == absolute }
                    }
                } ?: continue

                runCatching {
                    withTimeoutOrNull(15_000) {
                        source.loadLinks(episode.url) { links.add(it) }
                    } ?: false
                }
            } else {
                if (details != null && details.episodes.isNotEmpty()) continue
                runCatching {
                    withTimeoutOrNull(15_000) {
                        source.loadLinks(result.url) { links.add(it) }
                    } ?: false
                }
            }

            // More than one candidate usually just repeats the same episode.
            // For movies, collect a few YouTube uploads so there are multiple
            // playback options (and a better chance of a 1080p one).
            val preferredAvailable = links.any { it.quality == TurkMSSettings.preferredQuality }
            val enough = if (request.season != null) {
                preferredAvailable || links.size >= 4
            } else {
                links.size >= 3
            }
            if (enough) break
        }
        return links
    }

    private fun TmdbItem.toSearchResponse(api: MainAPI): SearchResponse {
        val url = if (isTv) "turkms://tv/$id" else "turkms://movie/$id"
        return if (isTv) {
            api.newTvSeriesSearchResponse(title, url, fix = false) {
                posterUrl = this@toSearchResponse.poster
                year = this@toSearchResponse.year
                score = this@toSearchResponse.score?.let { Score.from10(it) }
            }
        } else {
            api.newMovieSearchResponse(title, url, fix = false) {
                posterUrl = this@toSearchResponse.poster
                year = this@toSearchResponse.year
                score = this@toSearchResponse.score?.let { Score.from10(it) }
            }
        }
    }
}


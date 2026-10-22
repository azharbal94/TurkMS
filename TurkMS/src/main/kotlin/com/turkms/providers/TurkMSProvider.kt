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
import com.turkms.TmdbClient
import com.turkms.TmdbItem
import com.turkms.TurkMSSettings
import com.turkms.buildAggData
import com.turkms.buildSiteUrl
import com.turkms.normalizeTitle
import com.turkms.parseAggData
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class TurkMSProvider : MainAPI() {
    private companion object {
        const val MAX_TMDB_PAGES = 5
        const val TMDB_ENOUGH_RESULTS = 5
        const val MAX_CANDIDATES = 6
    }

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "TurkMS"
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = listOf(
        MainPageData("Turkish Movies", "tr_movies"),
        MainPageData("Turkish Series", "tr_series"),
        MainPageData("New Turkish Movies", "tr_movies_new"),
        MainPageData("Top Rated Turkish Series", "tr_series_top"),
        MainPageData("Now Playing in Turkey", "movie_now"),
        MainPageData("Trending This Week", "trending"),
    )

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

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
        // TMDB paginates 20 items per page; five pages is a good balance between
        // choice and request volume on mobile.
        if (page > MAX_TMDB_PAGES) return null
        val items: List<TmdbItem> = when (request.data) {
            // with_original_language=tr keeps the catalogs Turkish-only, instead of
            // mixing in whatever happens to be popular worldwide.
            "tr_movies" -> TmdbClient.list(
                "/discover/movie",
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "popularity.desc",
                    "page" to "$page",
                ),
            )

            "tr_series" -> TmdbClient.list(
                "/discover/tv",
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "popularity.desc",
                    "page" to "$page",
                ),
            )

            "tr_movies_new" -> TmdbClient.list(
                "/discover/movie",
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "primary_release_date.desc",
                    "vote_count.gte" to "5",
                    "release_date.lte" to today(),
                    "page" to "$page",
                ),
            )

            "tr_series_top" -> TmdbClient.list(
                "/discover/tv",
                mapOf(
                    "with_original_language" to "tr",
                    "sort_by" to "vote_average.desc",
                    "vote_count.gte" to "100",
                    "page" to "$page",
                ),
            )

            "movie_now" -> TmdbClient.list(
                "/movie/now_playing",
                mapOf("page" to "$page", "region" to "TR"),
            )

            "trending" -> TmdbClient.list("/trending/all/week", mapOf("page" to "$page"))
            else -> emptyList()
        }
        val list = items.map { it.toSearchResponse(this) }
        return newHomePageResponse(request, list, hasNext = page < MAX_TMDB_PAGES)
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
                        withTimeoutOrNull(20_000) { source.search(query) } ?: emptyList()
                    }.getOrDefault(emptyList())
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
                episodeData = { episode -> buildSiteUrl(source.key, episode.url) },
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
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
        val perSource = coroutineScope {
            sources.map { source ->
                async {
                    runCatching {
                        withTimeoutOrNull(45_000) { collectFromSource(source, request) }
                            ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        val seen = mutableSetOf<String>()
        return perSource.flatten()
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

        val candidates = mutableListOf<Pair<SiteResult, Int>>()
        for (query in queries) {
            val results = runCatching { source.search(query) }.getOrDefault(emptyList())
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

        // Search-result types are only a hint (canlidizi14 marks digital series as
        // movies); the detail page decides what a candidate actually is.
        val ranked = candidates
            .filter { it.second >= 50 }
            .sortedByDescending { it.second }
            .distinctBy { it.first.url }
            .take(MAX_CANDIDATES)

        if (request.season != null && request.episode != null) {
            for ((result, _) in ranked) {
                val details = runCatching { source.details(result.url) }.getOrNull() ?: continue
                if (details.type != TvType.TvSeries || details.episodes.isEmpty()) continue
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
                val ok = runCatching { source.loadLinks(episode.url) { links.add(it) } }.getOrDefault(false)
                if (ok && links.isNotEmpty()) break
            }
        } else {
            for ((result, _) in ranked) {
                val details = runCatching { source.details(result.url) }.getOrNull()
                if (details != null && details.episodes.isNotEmpty()) continue
                val ok = runCatching { source.loadLinks(result.url) { links.add(it) } }.getOrDefault(false)
                if (ok && links.isNotEmpty()) break
            }
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


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
        MainPageData("Popüler Filmler", "movie_popular"),
        MainPageData("Vizyondaki Filmler", "movie_now"),
        MainPageData("Yakında Vizyonda", "movie_upcoming"),
        MainPageData("Popüler Diziler", "tv_popular"),
        MainPageData("Yayında Olan Diziler", "tv_airing"),
        MainPageData("Bu Haftanın Trendleri", "trending"),
    )

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
            "movie_popular" -> TmdbClient.list("/movie/popular", mapOf("page" to "$page"))
            "movie_now" -> TmdbClient.list("/movie/now_playing", mapOf("page" to "$page", "region" to "TR"))
            "movie_upcoming" -> TmdbClient.list("/movie/upcoming", mapOf("page" to "$page", "region" to "TR"))
            "tv_popular" -> TmdbClient.list("/tv/popular", mapOf("page" to "$page"))
            "tv_airing" -> TmdbClient.list("/tv/on_the_air", mapOf("page" to "$page"))
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
        val data = buildAggData(title, original, year, null, null)
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

        val seasons = mutableListOf<Int>()
        val seasonsArray = json.optJSONArray("seasons")
        if (seasonsArray != null) {
            for (i in 0 until seasonsArray.length()) {
                val season = seasonsArray.optJSONObject(i) ?: continue
                val number = season.optInt("season_number")
                val count = season.optInt("episode_count")
                if (number > 0 && count > 0) seasons.add(number)
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
        for ((seasonNumber, seasonJson) in seasonData) {
            val array = seasonJson?.optJSONArray("episodes") ?: continue
            for (i in 0 until array.length()) {
                val ep = array.optJSONObject(i) ?: continue
                val number = ep.optInt("episode_number")
                if (number <= 0) continue
                val epName = ep.optString("name").takeIf { it.isNotBlank() }
                    ?: "S${seasonNumber}B$number"
                val epData = buildAggData(title, original, year, seasonNumber, number)
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
            return found
        }

        val request = parseAggData(data) ?: return false
        val links = collectLinks(request)
        for (link in links) callback(link)
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
                } ?: details.episodes.firstOrNull {
                    // Most Turkish sites expose a single season. Only use the
                    // season-agnostic match for season 1; otherwise S2E5 would
                    // silently resolve to the site's S1E5.
                    request.season == 1 && it.episode == request.episode
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


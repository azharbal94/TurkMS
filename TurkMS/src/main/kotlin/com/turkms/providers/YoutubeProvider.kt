package com.turkms.providers

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.ArabicSubtitles
import com.turkms.TmdbClient
import com.turkms.TurkMSSettings
import com.turkms.encode
import com.turkms.parseQuery
import com.turkms.parseYear
import com.turkms.sources.YoutubeMovieSource
import com.turkms.toLoadResponse
import com.turkms.toSearchResponse
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Standalone YouTube provider.
 *
 * YouTube has no browsable movie catalogue suitable for scraping, so the home
 * page uses TMDB lists and playback resolves each title through a YouTube
 * search. This keeps YouTube visible as its own provider instead of only being
 * a hidden fallback of the aggregator.
 */
class YoutubeProvider : MainAPI() {
    private val source = YoutubeMovieSource()

    override var mainUrl = source.mainUrl
    override var name = "YouTube"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val mainPage = listOf(
        MainPageData("Turkish Movies", "turkish"),
        MainPageData("Popular Movies", "popular"),
        MainPageData("Trending Movies", "trending"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val path = when (request.data) {
            "turkish" -> "/discover/movie"
            "popular" -> "/movie/popular"
            "trending" -> "/trending/movie/week"
            else -> return null
        }
        val params = if (request.data == "turkish") {
            mapOf("with_original_language" to "tr", "sort_by" to "popularity.desc")
        } else {
            emptyMap()
        }
        val items = TmdbClient.list(path, params).map { item ->
            newMovieSearchResponse(item.title, "$MOVIE_PREFIX${item.id}", fix = false) {
                posterUrl = item.poster
                year = item.year
                score = item.score?.let { Score.from10(it) }
            }
        }
        return newHomePageResponse(request, items)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        source.search(query).map { it.toSearchResponse(this) }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith(MOVIE_PREFIX)) {
            val id = url.removePrefix(MOVIE_PREFIX).toIntOrNull() ?: return null
            return loadTmdbMovie(id)
        }
        return source.details(url)?.toLoadResponse(this)
    }

    private suspend fun loadTmdbMovie(id: Int): LoadResponse? {
        val json = TmdbClient.movie(id) ?: return null
        val title = json.optString("title").ifBlank { json.optString("original_title") }
        if (title.isBlank()) return null
        val original = json.optString("original_title")
            .takeIf { it.isNotBlank() && it != title }
        val year = parseYear(json.optString("release_date"))
        val scoreValue = json.optDouble("vote_average").takeUnless { it.isNaN() || it == 0.0 }
        val imdb = TmdbClient.imdbId(json)
        val data = "$PLAY_PREFIX?title=${encode(title)}&original=${encode(original ?: "")}" +
            "&imdb=${encode(imdb ?: "")}"

        return newMovieLoadResponse(title, "$MOVIE_PREFIX$id", TvType.Movie, dataUrl = data) {
            posterUrl = TmdbClient.poster(json.optString("poster_path"))
            backgroundPosterUrl = TmdbClient.backdrop(json.optString("backdrop_path"))
            plot = json.optString("overview").takeIf { it.isNotBlank() }
            this.year = year
            duration = json.optInt("runtime").takeIf { it > 0 }
            tags = TmdbClient.genres(json)
            actors = TmdbClient.actors(json)
            score = scoreValue?.let { Score.from10(it) }
            trailers = TmdbClient.trailers(json)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var imdb: String? = null
        val target = if (data.startsWith(PLAY_PREFIX)) {
            val params = parseQuery(data)
            val title = params["title"] ?: return false
            val original = params["original"]
            imdb = params["imdb"]?.takeIf { it.isNotBlank() }
            listOfNotNull(title, original)
                .firstNotNullOfOrNull { query -> source.search(query).firstOrNull() }
                ?.url ?: return false
        } else {
            data
        }
        val ok = source.loadLinks(target) { callback(it) }

        if (TurkMSSettings.arabicSubtitles) {
            val resolved = imdb ?: runCatching {
                withTimeoutOrNull(10_000) {
                    val title = source.details(target)?.name ?: return@withTimeoutOrNull null
                    TmdbClient.resolveImdb(title, null, null, isTv = false)
                }
            }.getOrNull()
            ArabicSubtitles.find(resolved, isTv = false).forEach { subtitleCallback(it) }
        }
        return ok
    }

    private companion object {
        const val MOVIE_PREFIX = "turkms://yt/movie/"
        const val PLAY_PREFIX = "turkms://yt/play?"
    }
}

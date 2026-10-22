package com.turkms

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse

fun SiteResult.toSearchResponse(api: MainAPI): com.lagradost.cloudstream3.SearchResponse {
    return if (type == TvType.TvSeries) {
        api.newTvSeriesSearchResponse(name, url) {
            posterUrl = this@toSearchResponse.poster
            year = this@toSearchResponse.year
        }
    } else {
        api.newMovieSearchResponse(name, url) {
            posterUrl = this@toSearchResponse.poster
            year = this@toSearchResponse.year
        }
    }
}

suspend fun SiteDetails.toLoadResponse(
    api: MainAPI,
    movieDataUrl: String = url,
    episodeData: ((SiteEpisode) -> String)? = null,
): LoadResponse {
    val actorData = actors.map { ActorData(Actor(it)) }
    val scoreValue = score?.let { Score.from10(it) }
    return if (type == TvType.TvSeries) {
        api.newTvSeriesLoadResponse(name, url, TvType.TvSeries, episodes.map { episode ->
            val data = episodeData?.invoke(episode) ?: episode.url
            api.newEpisode(data, initializer = {
                this.name = episode.name
                this.season = episode.season
                this.episode = episode.episode
                this.posterUrl = episode.poster ?: this@toLoadResponse.poster
                this.description = null
            }, fix = false)
        }) {
            posterUrl = this@toLoadResponse.poster
            plot = this@toLoadResponse.plot
            year = this@toLoadResponse.year
            tags = this@toLoadResponse.tags
            actors = actorData
            score = scoreValue
        }
    } else {
        api.newMovieLoadResponse(name, url, TvType.Movie, dataUrl = movieDataUrl) {
            posterUrl = this@toLoadResponse.poster
            plot = this@toLoadResponse.plot
            year = this@toLoadResponse.year
            tags = this@toLoadResponse.tags
            actors = actorData
            score = scoreValue
        }
    }
}

fun SiteResult.posterOrNull(): String? = poster

fun buildSiteUrl(sourceKey: String, url: String): String =
    "turkms://site?key=${encode(sourceKey)}&url=${encode(url)}"

data class SiteUrlParts(val key: String, val url: String)

fun parseSiteUrl(data: String): SiteUrlParts? {
    if (!data.startsWith("turkms://site?")) return null
    val params = parseQuery(data)
    val key = params["key"] ?: return null
    val url = params["url"] ?: return null
    return SiteUrlParts(key, url)
}

fun parseQuery(data: String): Map<String, String> {
    val query = data.substringAfter("?", "")
    if (query.isBlank()) return emptyMap()
    return query.split("&").mapNotNull { part ->
        if (part.isBlank()) return@mapNotNull null
        val key = part.substringBefore("=")
        val value = part.substringAfter("=", "")
        decode(key) to decode(value)
    }.toMap()
}

fun decode(value: String): String = try {
    java.net.URLDecoder.decode(value, "UTF-8")
} catch (_: Exception) {
    value
}

data class AggRequest(
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    /** Episode number across the whole show, used by continuously numbered sites. */
    val absoluteEpisode: Int? = null,
)

fun buildAggData(
    title: String,
    originalTitle: String?,
    year: Int?,
    season: Int?,
    episode: Int?,
    absoluteEpisode: Int? = null,
): String {
    val kind = if (season != null && episode != null) "episode" else "movie"
    return "turkms://agg?kind=$kind" +
        "&title=${encode(title)}" +
        "&original=${encode(originalTitle ?: "")}" +
        "&year=${year ?: 0}" +
        "&season=${season ?: 0}" +
        "&episode=${episode ?: 0}" +
        "&abs=${absoluteEpisode ?: 0}"
}

fun parseAggData(data: String): AggRequest? {
    if (!data.startsWith("turkms://agg?")) return null
    val params = parseQuery(data)
    val title = params["title"]?.takeIf { it.isNotBlank() } ?: return null
    return AggRequest(
        title = title,
        originalTitle = params["original"]?.takeIf { it.isNotBlank() },
        year = params["year"]?.toIntOrNull()?.takeIf { it > 0 },
        season = params["season"]?.toIntOrNull()?.takeIf { it > 0 },
        episode = params["episode"]?.toIntOrNull()?.takeIf { it > 0 },
        absoluteEpisode = params["abs"]?.toIntOrNull()?.takeIf { it > 0 },
    )
}

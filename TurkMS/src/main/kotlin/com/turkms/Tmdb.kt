package com.turkms

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject

data class TmdbItem(
    val id: Int,
    val title: String,
    val isTv: Boolean,
    val year: Int?,
    val poster: String?,
    val score: Double?,
    val overview: String?,
)

object TmdbClient {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE = "https://image.tmdb.org/t/p"

    suspend fun request(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val all = params + mapOf(
            "api_key" to TurkMSSettings.effectiveTmdbKey(),
            "language" to "en-US",
            "include_adult" to "false",
        )
        return runCatching {
            JSONObject(
                app.get(
                    "$BASE$path",
                    params = all,
                    headers = mapOf("Accept" to "application/json"),
                    timeout = TurkMSSettings.requestTimeout,
                ).text
            )
        }.getOrNull()
    }

    fun poster(path: String?, size: String = "w500"): String? =
        path?.takeIf { it.isNotBlank() }?.let { "$IMAGE/$size$it" }

    fun backdrop(path: String?): String? = poster(path, "w1280")

    private fun parseItem(json: JSONObject): TmdbItem? {
        val mediaType = json.optString("media_type")
        // /search/multi and /trending return people too - they are not playable content.
        if (mediaType == "person") return null
        val isTv = mediaType == "tv" || (!json.has("title") && json.has("name"))
        val title = json.optString(if (isTv) "name" else "title")
        if (title.isBlank()) return null
        val date = json.optString(if (isTv) "first_air_date" else "release_date")
        return TmdbItem(
            id = json.optInt("id"),
            title = title,
            isTv = isTv,
            year = parseYear(date),
            poster = poster(json.optString("poster_path")),
            score = json.optDouble("vote_average").takeUnless { it.isNaN() || it == 0.0 },
            overview = json.optString("overview").takeIf { it.isNotBlank() },
        )
    }

    private fun parseList(array: JSONArray?): List<TmdbItem> {
        if (array == null) return emptyList()
        val result = mutableListOf<TmdbItem>()
        for (i in 0 until array.length()) {
            parseItem(array.optJSONObject(i) ?: continue)?.let { result.add(it) }
        }
        return result
    }

    suspend fun list(path: String, params: Map<String, String> = emptyMap()): List<TmdbItem> {
        val json = request(path, params) ?: return emptyList()
        return parseList(json.optJSONArray("results"))
    }

    suspend fun searchMulti(query: String): List<TmdbItem> {
        val json = request(
            "/search/multi",
            mapOf("query" to query, "page" to "1")
        ) ?: return emptyList()
        return parseList(json.optJSONArray("results")).filter { it.id > 0 }
    }

    suspend fun movie(id: Int): JSONObject? = request(
        "/movie/$id",
        mapOf("append_to_response" to "credits,videos,recommendations,external_ids")
    )

    suspend fun tv(id: Int): JSONObject? = request(
        "/tv/$id",
        mapOf("append_to_response" to "credits,videos,recommendations,external_ids")
    )

    suspend fun season(tvId: Int, season: Int): JSONObject? =
        request("/tv/$tvId/season/$season")

    fun recommendations(details: JSONObject): List<TmdbItem> {
        val array = details.optJSONObject("recommendations")?.optJSONArray("results")
        return parseList(array).take(20)
    }

    fun actors(details: JSONObject): List<com.lagradost.cloudstream3.ActorData> {
        val cast = details.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        val result = mutableListOf<com.lagradost.cloudstream3.ActorData>()
        for (i in 0 until minOf(cast.length(), 15)) {
            val actor = cast.optJSONObject(i) ?: continue
            val name = actor.optString("name")
            if (name.isBlank()) continue
            result.add(
                com.lagradost.cloudstream3.ActorData(
                    com.lagradost.cloudstream3.Actor(
                        name = name,
                        image = poster(actor.optString("profile_path"), "w185")
                    ),
                    roleString = actor.optString("character").takeIf { it.isNotBlank() }
                )
            )
        }
        return result
    }

    fun trailers(details: JSONObject): MutableList<com.lagradost.cloudstream3.TrailerData> {
        val result = mutableListOf<com.lagradost.cloudstream3.TrailerData>()
        val videos = details.optJSONObject("videos")?.optJSONArray("results") ?: return result
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            if (!video.optString("site").equals("YouTube", ignoreCase = true)) continue
            val key = video.optString("key")
            if (key.isBlank()) continue
            if (!video.optString("type").equals("Trailer", ignoreCase = true) && result.size >= 2) continue
            result.add(
                com.lagradost.cloudstream3.TrailerData(
                    extractorUrl = "https://www.youtube.com/watch?v=$key",
                    referer = "https://www.themoviedb.org/",
                    raw = false,
                )
            )
            if (result.size >= 3) break
        }
        return result
    }

    fun genres(details: JSONObject): List<String> {
        val array = details.optJSONArray("genres") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        }
        return result
    }

    fun imdbId(details: JSONObject): String? =
        details.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
}

package com.turkms.providers

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.ArabicSubtitles
import com.turkms.SiteSource
import com.turkms.TmdbClient
import com.turkms.TurkMSSettings
import com.turkms.sources.CanliDizi14Source
import com.turkms.sources.DdiziSource
import com.turkms.sources.DiziIzleNowSource
import com.turkms.sources.TrDiziIzleSource
import com.turkms.sortForPreferredQuality
import com.turkms.toLoadResponse
import com.turkms.toSearchResponse
import kotlinx.coroutines.withTimeoutOrNull

abstract class BaseSiteProvider(private val site: SiteSource) : MainAPI() {
    init {
        name = site.name
        mainUrl = site.mainUrl
        lang = "tr"
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = listOf(MainPageData("Latest & Archive", "latest"))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data != "latest") return null
        // A dead or blocking site must not take the whole home screen down.
        val items = runCatching { site.latest(page) }
            .getOrDefault(emptyList())
            .map { it.toSearchResponse(this) }
        // Sites return 30-90 cards per app page. A short page means the site's
        // archive is exhausted, so the client can stop asking for more. Ddizi
        // exposes a single latest page, so it never has a next page.
        val hasNext = site.key != "ddizi" && items.size >= 10
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return site.search(query).map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        return site.details(url)?.toLoadResponse(this)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = mutableListOf<ExtractorLink>()
        site.loadLinks(data) { links.add(it) }
        for (link in links.sortForPreferredQuality()) callback(link)
        addArabicSubtitles(data, subtitleCallback)
        return links.isNotEmpty()
    }

    private suspend fun addArabicSubtitles(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!TurkMSSettings.arabicSubtitles) return
        runCatching {
            val details = withTimeoutOrNull(10_000) { site.details(url) } ?: return
            val isTv = details.type == TvType.TvSeries
            val episode = details.episodes.firstOrNull { it.url == url }
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
            ).forEach(subtitleCallback)
        }
    }
}

class CanliDizi14Provider : BaseSiteProvider(CanliDizi14Source())
class DdiziProvider : BaseSiteProvider(DdiziSource())
class DiziIzleNowProvider : BaseSiteProvider(DiziIzleNowSource())
class TrDiziIzleProvider : BaseSiteProvider(TrDiziIzleSource())

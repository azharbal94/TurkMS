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
import com.turkms.SiteEpisodeData
import com.turkms.SiteSource
import com.turkms.TmdbClient
import com.turkms.TurkMSSettings
import com.turkms.buildSiteEpisodeData
import com.turkms.parseSiteEpisodeData
import com.turkms.sources.AtvSource
import com.turkms.sources.CanliDizi14Source
import com.turkms.sources.DdiziSource
import com.turkms.sources.DiziabiSource
import com.turkms.sources.DiziIzleNowSource
import com.turkms.sources.DiziboxSource
import com.turkms.sources.DizimolySource
import com.turkms.sources.Izle7Source
import com.turkms.sources.JetFilmizleSource
import com.turkms.sources.KanalDSource
import com.turkms.sources.NowTvSource
import com.turkms.sources.ShowTvSource
import com.turkms.sources.StartvSource
import com.turkms.sources.TrDiziIzleSource
import com.turkms.sources.Trt1Source
import com.turkms.sources.TvDizilerSource
import com.turkms.sources.YabancidiziSource
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

    override val supportedTypes: Set<TvType> = mutableSetOf<TvType>().apply {
        if (site.supportsMovies) add(TvType.Movie)
        if (site.supportsSeries) add(TvType.TvSeries)
        if (isEmpty()) {
            add(TvType.Movie)
            add(TvType.TvSeries)
        }
    }
    override val hasMainPage = true
    override val mainPage = listOf(MainPageData("Latest & Archive", "latest"))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data != "latest") return null
        // A dead or blocking site must not take the whole home screen down.
        val items = runCatching {
            withTimeoutOrNull(45_000) { site.latest(page) }.orEmpty()
        }.getOrDefault(emptyList())
            .map { it.toSearchResponse(this) }
        // Sites return 30-90 cards per app page. A short page means the site's
        // archive is exhausted, so the client can stop asking for more.
        val hasNext = items.size >= 10
        return newHomePageResponse(request, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return site.search(query).map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse? {
        val details = site.details(url) ?: return null
        return details.toLoadResponse(
            this,
            movieDataUrl = url,
            episodeData = { episode ->
                buildSiteEpisodeData(site.key, episode, details.name, details.year)
            },
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val meta = parseSiteEpisodeData(data)
        val target = meta?.url ?: data
        val links = mutableListOf<ExtractorLink>()
        site.loadLinks(target) { links.add(it) }
        for (link in links.sortForPreferredQuality()) callback(link)
        if (meta != null) {
            addArabicSubtitles(meta, subtitleCallback)
        } else {
            addArabicSubtitles(target, subtitleCallback)
        }
        return links.isNotEmpty()
    }

    private suspend fun addArabicSubtitles(
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
class DizimolyProvider : BaseSiteProvider(DizimolySource())
class DiziabiProvider : BaseSiteProvider(DiziabiSource())
class TvDizilerProvider : BaseSiteProvider(TvDizilerSource())
class DiziboxProvider : BaseSiteProvider(DiziboxSource())
class YabancidiziProvider : BaseSiteProvider(YabancidiziSource())
class JetFilmizleProvider : BaseSiteProvider(JetFilmizleSource())
class NowTvProvider : BaseSiteProvider(NowTvSource())
class KanalDProvider : BaseSiteProvider(KanalDSource())
class AtvProvider : BaseSiteProvider(AtvSource())
class ShowTvProvider : BaseSiteProvider(ShowTvSource())
class Trt1Provider : BaseSiteProvider(Trt1Source())
class StartvProvider : BaseSiteProvider(StartvSource())
class Izle7Provider : BaseSiteProvider(Izle7Source())

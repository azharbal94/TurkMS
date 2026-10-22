package com.turkms.providers

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.turkms.SiteSource
import com.turkms.sources.CanliDizi14Source
import com.turkms.sources.DdiziSource
import com.turkms.sources.DiziIzleNowSource
import com.turkms.sources.TrDiziIzleSource
import com.turkms.sortForPreferredQuality
import com.turkms.toLoadResponse
import com.turkms.toSearchResponse

abstract class BaseSiteProvider(private val site: SiteSource) : MainAPI() {
    init {
        name = site.name
        mainUrl = site.mainUrl
        lang = "tr"
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
        return links.isNotEmpty()
    }
}

class CanliDizi14Provider : BaseSiteProvider(CanliDizi14Source())
class DdiziProvider : BaseSiteProvider(DdiziSource())
class DiziIzleNowProvider : BaseSiteProvider(DiziIzleNowSource())
class TrDiziIzleProvider : BaseSiteProvider(TrDiziIzleSource())

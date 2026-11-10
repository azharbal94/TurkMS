package com.turkms

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.turkms.providers.AtvProvider
import com.turkms.providers.CanliDizi14Provider
import com.turkms.providers.DailymotionProvider
import com.turkms.providers.DdiziProvider
import com.turkms.providers.DiziabiProvider
import com.turkms.providers.DiziIzleNowProvider
import com.turkms.providers.DiziboxProvider
import com.turkms.providers.DizimolyProvider
import com.turkms.providers.Izle7Provider
import com.turkms.providers.JetFilmizleProvider
import com.turkms.providers.KanalDProvider
import com.turkms.providers.NowTvProvider
import com.turkms.providers.ShowTvProvider
import com.turkms.providers.StartvProvider
import com.turkms.providers.TrDiziIzleProvider
import com.turkms.providers.Trt1Provider
import com.turkms.providers.TurkMSProvider
import com.turkms.providers.TvDizilerProvider
import com.turkms.providers.YabancidiziProvider
import com.turkms.providers.YoutubeProvider

@CloudstreamPlugin
class TurkMSPlugin : Plugin() {
    override fun load(context: Context) {
        TurkMSSettings.load(context)

        // Main TMDB-powered provider with multi-site playback aggregation.
        registerMainAPI(TurkMSProvider())

        // Native providers, useful as direct fallbacks when a site changes its layout.
        registerMainAPI(CanliDizi14Provider())
        registerMainAPI(DdiziProvider())
        registerMainAPI(DiziIzleNowProvider())
        registerMainAPI(TrDiziIzleProvider())
        registerMainAPI(DizimolyProvider())
        registerMainAPI(DiziabiProvider())
        registerMainAPI(TvDizilerProvider())
        registerMainAPI(DiziboxProvider())
        registerMainAPI(YabancidiziProvider())
        registerMainAPI(JetFilmizleProvider())
        registerMainAPI(NowTvProvider())
        registerMainAPI(KanalDProvider())
        registerMainAPI(AtvProvider())
        registerMainAPI(ShowTvProvider())
        registerMainAPI(Trt1Provider())
        registerMainAPI(StartvProvider())
        registerMainAPI(Izle7Provider())
        registerMainAPI(DailymotionProvider())
        registerMainAPI(YoutubeProvider())

        openSettings = { ctx -> showSettingsDialog(ctx) }
    }
}

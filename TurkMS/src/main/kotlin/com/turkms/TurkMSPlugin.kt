package com.turkms

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.turkms.providers.CanliDizi14Provider
import com.turkms.providers.DdiziProvider
import com.turkms.providers.DiziIzleNowProvider
import com.turkms.providers.TrDiziIzleProvider
import com.turkms.providers.TurkMSProvider
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
        registerMainAPI(YoutubeProvider())

        openSettings = { ctx -> showSettingsDialog(ctx) }
    }
}

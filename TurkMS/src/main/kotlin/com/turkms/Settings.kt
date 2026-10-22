package com.turkms

import android.content.Context
import android.content.SharedPreferences

/**
 * Small SharedPreferences wrapper. Everything is kept in the "turkms" preference file so
 * settings survive plugin reloads and app restarts.
 */
object TurkMSSettings {
    private const val PREFS = "turkms"
    private const val KEY_TMDB = "tmdb_api_key"
    private const val KEY_CANLI = "source_canlidizi14"
    private const val KEY_DDIZI = "source_ddizi"
    private const val KEY_NOW = "source_diziizlenow"
    private const val KEY_TR = "source_trdiziizle"
    private const val KEY_YT = "source_youtube"
    private const val KEY_QUALITY = "preferred_quality"
    private const val KEY_TIMEOUT = "request_timeout"

    /** Public demo key. A personal TMDB v3 key can be entered in the plugin settings. */
    const val FALLBACK_TMDB_KEY = "4e44d9029b1270a757cddc766a1bcb63"

    var tmdbApiKey: String = ""
    var sourceCanliDizi14: Boolean = true
    var sourceDdizi: Boolean = true
    var sourceDiziIzleNow: Boolean = true
    var sourceTrDiziIzle: Boolean = true
    var sourceYoutube: Boolean = true
    var preferredQuality: Int = 1080
    var requestTimeout: Long = 20L

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        tmdbApiKey = p.getString(KEY_TMDB, "") ?: ""
        sourceCanliDizi14 = p.getBoolean(KEY_CANLI, true)
        sourceDdizi = p.getBoolean(KEY_DDIZI, true)
        sourceDiziIzleNow = p.getBoolean(KEY_NOW, true)
        sourceTrDiziIzle = p.getBoolean(KEY_TR, true)
        sourceYoutube = p.getBoolean(KEY_YT, true)
        preferredQuality = p.getInt(KEY_QUALITY, 1080)
        requestTimeout = p.getLong(KEY_TIMEOUT, 20L)
    }

    fun save(
        context: Context,
        tmdbKey: String,
        canli: Boolean,
        ddizi: Boolean,
        now: Boolean,
        tr: Boolean,
        youtube: Boolean,
        quality: Int,
        timeout: Long,
    ) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putString(KEY_TMDB, tmdbKey.trim())
            .putBoolean(KEY_CANLI, canli)
            .putBoolean(KEY_DDIZI, ddizi)
            .putBoolean(KEY_NOW, now)
            .putBoolean(KEY_TR, tr)
            .putBoolean(KEY_YT, youtube)
            .putInt(KEY_QUALITY, quality)
            .putLong(KEY_TIMEOUT, timeout)
            .apply()
        prefs = p
        tmdbApiKey = tmdbKey.trim()
        sourceCanliDizi14 = canli
        sourceDdizi = ddizi
        sourceDiziIzleNow = now
        sourceTrDiziIzle = tr
        sourceYoutube = youtube
        preferredQuality = quality
        requestTimeout = timeout
    }

    fun effectiveTmdbKey(): String = tmdbApiKey.ifBlank { FALLBACK_TMDB_KEY }
}

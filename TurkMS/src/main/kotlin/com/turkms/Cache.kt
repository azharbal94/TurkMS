package com.turkms

import java.util.concurrent.ConcurrentHashMap

/**
 * Small in-memory cache for site searches, detail pages and TMDB lookups.
 *
 * Switching between episodes of the same show otherwise repeats the same
 * search and series-page requests for every episode. The cache is deliberately
 * short-lived so newly published episodes still show up quickly.
 */
object TurkMSCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 300

    private data class Entry(val at: Long, val value: Any)

    private val entries = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            entries.remove(key)
            return null
        }
        return entry.value as T
    }

    fun put(key: String, value: Any) {
        if (entries.size >= MAX_ENTRIES) {
            val oldest = entries.entries.minByOrNull { it.value.at }?.key
            if (oldest != null) entries.remove(oldest)
        }
        entries[key] = Entry(System.currentTimeMillis(), value)
    }

    suspend fun <T : Any> getOrPut(key: String, loader: suspend () -> T?): T? {
        get<T>(key)?.let { return it }
        val value = loader() ?: return null
        put(key, value)
        return value
    }
}

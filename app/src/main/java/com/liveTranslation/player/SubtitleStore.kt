package com.liveTranslation.player

import java.util.TreeMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe, timestamp-indexed store for pre-computed subtitles.
 *
 * The [HiddenTrackPlayer] writes entries as translation results arrive (keyed
 * by the audio window's start position in the source media). The UI ticker
 * reads the nearest entry for the visible player's current position.
 */
class SubtitleStore {

    private val tree = TreeMap<Long, Entry>()
    private val lock = ReentrantReadWriteLock()

    data class Entry(
        val startMs: Long,
        val endMs: Long,
        val original: String,
        val translated: String
    )

    /** Insert a subtitle with an explicit display range [startMs, endMs]. */
    fun put(startMs: Long, endMs: Long, original: String, translated: String) {
        val safeStart = minOf(startMs, endMs)
        val safeEnd = maxOf(startMs, endMs)
        lock.write { tree[safeStart] = Entry(safeStart, safeEnd, original, translated) }
    }

    /**
     * Return the entry active at [positionMs], or null if no active subtitle.
     */
    fun getAt(positionMs: Long): Entry? = lock.read {
        val entry = tree.floorEntry(positionMs)?.value ?: return null
        if (positionMs <= entry.endMs) entry else null
    }

    /**
     * Like [getAt] but with a sticky tail: if no subtitle's display range covers
     * [positionMs], return the most recent expired entry, provided it expired
     * less than [maxStaleMs] ago. This bridges the gap when ASR/MT latency
     * exceeds the natural display window — the previous translation stays on
     * screen until the next one arrives, instead of disappearing entirely.
     *
     * Avoids returning entries from far in the past (e.g. after a long pause
     * or when the user seeks backward into an un-translated region).
     */
    fun getNearestForDisplay(positionMs: Long, maxStaleMs: Long = 15_000L): Entry? = lock.read {
        val entry = tree.floorEntry(positionMs)?.value ?: return null
        if (positionMs <= entry.endMs) return entry
        if (positionMs - entry.endMs <= maxStaleMs) entry else null
    }

    /** Evict all entries with key < [beforeMs] to bound memory usage. */
    fun evictBefore(beforeMs: Long) {
        lock.write { tree.headMap(beforeMs).clear() }
    }

    fun clear() {
        lock.write { tree.clear() }
    }

    fun size(): Int = lock.read { tree.size }
}

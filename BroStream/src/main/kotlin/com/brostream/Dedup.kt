package com.brostream

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic duplicate handling. Nothing here depends on which row CloudStream loads first:
 * the same input list always produces the same output.
 */
internal object Titles {
    private val label = Regex("\\b(?:4k|8k|2160p|1440p|1080p|720p|480p|360p|uhd|full hd|hd|full video|full movie)\\b")
    private val bracketPrefix = Regex("^\\s*(?:\\[[^\\]]{1,40}]|\\([^)]{1,40}\\))\\s*")
    private val separatorPrefix = Regex("^\\s*[^-–—|:]{2,30}?\\s+[-–—|:]\\s+")

    /** Lower-cased words with studio prefixes, resolution labels and punctuation removed. */
    fun normalize(title: String): String {
        var text = Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        repeat(2) { text = text.replace(bracketPrefix, "") }
        val stripped = text.replace(separatorPrefix, "")
        if (stripped != text && words(stripped).size >= 2) text = stripped
        return words(text.replace(label, " ")).joinToString(" ")
    }

    private fun words(text: String): List<String> =
        text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter(String::isNotEmpty)
}

internal data class Fingerprint(
    val titleKey: String,
    val tokens: Set<String>,
    val performers: Set<String>,
    val durationSec: Int,
    val thumbKey: String,
)

internal fun fingerprint(item: ItemData): Fingerprint {
    val titleKey = Titles.normalize(item.title)
    val thumbName = attempt { URI(item.poster.orEmpty()).path }.orEmpty()
        .substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
    return Fingerprint(
        titleKey = titleKey,
        tokens = titleKey.split(' ').filter(String::isNotEmpty).toSet(),
        performers = item.performers.map { Titles.normalize(it) }.filter(String::isNotEmpty).toSet(),
        durationSec = item.durationSec,
        // Short names like "1" or "default" are shared by unrelated videos; only hash-like names identify one.
        thumbKey = thumbName.takeIf { it.length >= 10 }.orEmpty(),
    )
}

internal fun dice(a: Set<String>, b: Set<String>): Double =
    if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)

internal fun sameVideo(a: Fingerprint, b: Fingerprint): Boolean {
    val durationKnown = a.durationSec > 0 && b.durationSec > 0
    if (durationKnown && abs(a.durationSec - b.durationSec) > 3) return false
    if (a.titleKey.isNotEmpty() && a.titleKey == b.titleKey) return true
    val sharedThumb = a.thumbKey.isNotEmpty() && a.thumbKey == b.thumbKey
    val sharedPerformer = a.performers.intersect(b.performers).isNotEmpty()
    val threshold = when {
        sharedThumb -> 0.5
        sharedPerformer -> 0.7
        durationKnown -> 0.8
        else -> 0.9
    }
    return dice(a.tokens, b.tokens) >= threshold
}

/** Provider-scoped identity: the numeric ID in the watch URL, else its last path segment. */
internal fun canonicalId(item: ItemData): String {
    val path = attempt { URI(item.url).path }.orEmpty()
    val numeric = Regex("/(?:videos?|watch|v)/(\\d+)(?:/|$)").find(path)?.groupValues?.get(1)
    val key = numeric ?: path.trim('/').substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
    return "${item.source}:${key.ifEmpty { catalogueUrlKey(item.url).orEmpty() }}"
}

internal data class SourceProfile(val reliability: Int, val speed: Int)

private val resolutionLabel = Regex("\\b(?:(2160|1440|1080|720|480|360)p|4k)\\b", RegexOption.IGNORE_CASE)

/** Higher is better: resolution first, then provider reliability, then loading speed. */
internal fun copyScore(item: ItemData, profile: (String) -> SourceProfile?): Int {
    val match = resolutionLabel.find(item.title)
    val resolution = match?.groupValues?.get(1)?.toIntOrNull() ?: if (match != null) 2160 else 0
    val source = profile(item.source)
    return resolution * 1000 + (source?.reliability ?: 0) * 10 + (source?.speed ?: 0)
}

internal class Deduplicator(
    private val maxPerRow: Int = 1000,
    private val profile: (String) -> SourceProfile?,
) {
    private class Seen(val page: Int, val canonical: String, val print: Fingerprint)

    private val rows = HashMap<String, ArrayList<Seen>>()

    /** Collapses duplicates in one batch, keeping each video at its first position with its best copy. */
    fun collapse(items: List<ItemData>): List<ItemData> {
        val kept = ArrayList<ItemData>()
        val prints = ArrayList<Fingerprint>()
        for (item in items) {
            val canonical = canonicalId(item)
            val print = fingerprint(item)
            val index = kept.indices.firstOrNull { canonicalId(kept[it]) == canonical || sameVideo(prints[it], print) }
            if (index == null) {
                kept.add(item); prints.add(print)
            } else if (copyScore(item, profile) > copyScore(kept[index], profile)) {
                kept[index] = item; prints[index] = print
            }
        }
        return kept
    }

    /**
     * Returns up to [limit] videos for one row page that were not already shown on earlier pages of
     * the same row. Page 1 starts the row afresh; reloading page N forgets page N and later.
     */
    @Synchronized
    fun admit(row: String, page: Int, items: List<ItemData>, limit: Int): List<ItemData> {
        require(page >= 1 && limit >= 0)
        val seen = rows.getOrPut(row) { ArrayList() }
        seen.removeAll { page == 1 || it.page >= page }
        val result = ArrayList<ItemData>()
        for (item in collapse(items)) {
            if (result.size >= limit) break
            val canonical = canonicalId(item)
            val print = fingerprint(item)
            if (seen.any { it.canonical == canonical || sameVideo(it.print, print) }) continue
            result.add(item)
            seen.add(Seen(page, canonical, print))
        }
        while (seen.size > maxPerRow) seen.removeAt(0)
        return result
    }
}

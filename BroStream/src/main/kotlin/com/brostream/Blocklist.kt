package com.brostream

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Corrections maintained on GitHub (blocklist.json on the builds branch), so a video or a word can be
 * blocked without publishing a new extension version.
 */
internal data class Blocklist(val ids: Set<String> = emptySet(), val rejectTerms: Regex? = null) {
    /** Why the item is blocked, or null. */
    fun blocks(item: ItemData): String? {
        if (canonicalId(item) in ids) return "blocklisted video"
        val terms = rejectTerms ?: return null
        val text = (listOf(item.title, item.description) + item.tags + item.performers)
        return text.firstNotNullOfOrNull { terms.find(it)?.value }?.let { "blocklisted term: $it" }
    }

    companion object {
        val EMPTY = Blocklist()

        /** Malformed files are ignored rather than allowed to break browsing. */
        fun parse(json: String, mapper: ObjectMapper): Blocklist = attempt {
            val root = mapper.readTree(json)
            val ids = root.path("ids").map { it.asText() }.filter(String::isNotBlank).toSet()
            val terms = root.path("rejectTerms").map { it.asText() }.filter(String::isNotBlank)
            Blocklist(ids, if (terms.isEmpty()) null else wordRegex(*terms.toTypedArray()))
        } ?: EMPTY
    }
}

/** Counts of why items were rejected or quarantined, to see which rules are doing the work. */
internal class PolicyStats {
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun record(assessment: Assessment) {
        val key = "${assessment.verdict}: ${assessment.reason.substringBefore(':')}"
        counts[key] = (counts[key] ?: 0) + 1
    }

    @Synchronized
    fun snapshot(): Map<String, Int> = counts.toSortedMap()
}

internal data class StoredVerdict(val verdict: Verdict, val at: Long)

/** Remembers the outcome of inspecting a video so unclear ones are not fetched again on every load. */
internal interface VerdictStore {
    fun get(id: String): StoredVerdict?
    fun put(id: String, verdict: StoredVerdict)
}

internal class MemoryVerdictStore(private val max: Int = 4000) : VerdictStore {
    private val map = LinkedHashMap<String, StoredVerdict>()

    @Synchronized override fun get(id: String) = map[id]

    @Synchronized fun recent(limit: Int): List<Pair<String, StoredVerdict>> =
        map.entries.toList().takeLast(limit).map { it.key to it.value }

    @Synchronized override fun put(id: String, verdict: StoredVerdict) {
        map.remove(id)
        map[id] = verdict
        while (map.size > max) map.remove(map.keys.first())
    }
}

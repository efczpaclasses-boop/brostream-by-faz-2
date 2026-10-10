package com.brostreamah

import com.fasterxml.jackson.databind.ObjectMapper
import com.brostreamah.sources.Web

const val BLOCKLIST_URL = "https://raw.githubusercontent.com/efczpaclasses-boop/brostream-by-faz-2/builds-ah/blocklist.json"

/** Corrections from blocklist.json on GitHub; an unreachable file means no extra blocks. */
internal class BlocklistLoader(private val mapper: ObjectMapper, private val url: String = BLOCKLIST_URL) {
    private val cache = ExpiringCache<Blocklist>(max = 1, ttlMillis = 6 * 3_600_000L)

    suspend fun get(): Blocklist {
        cache.get("list")?.let { return it }
        val raw = attempt { Web.fetch(url, mapOf("User-Agent" to Web.USER_AGENT), 200_000) }
        val loaded = raw?.takeIf { it.code in 200..299 }?.let { Blocklist.parse(String(it.body, Charsets.UTF_8), mapper) } ?: Blocklist.EMPTY
        cache.put("list", loaded)
        return loaded
    }
}

/** Inspection outcomes survive restarts, so unclear videos are not downloaded again every session. */
internal class PersistentVerdictStore(private val mapper: ObjectMapper) : VerdictStore {
    private val memory = MemoryVerdictStore()
    private var loaded = false
    private var pending = 0

    private fun load() {
        if (loaded) return
        loaded = true
        Persist.read(KEY)?.let { text ->
            attempt { mapper.readTree(text) }?.fields()?.forEach { (id, node) ->
                val verdict = attempt { Verdict.valueOf(node.path("v").asText()) } ?: return@forEach
                memory.put(id, StoredVerdict(verdict, node.path("t").asLong()))
            }
        }
    }

    @Synchronized override fun get(id: String): StoredVerdict? { load(); return memory.get(id) }

    @Synchronized override fun put(id: String, verdict: StoredVerdict) {
        load()
        memory.put(id, verdict)
        if (++pending >= 20) { pending = 0; save() }
    }

    private fun save() {
        // Only the newest entries are kept, so the stored value stays small.
        val snapshot = memory.recent(1500).associate { (id, v) -> id to mapOf("v" to v.verdict.name, "t" to v.at) }
        attempt { mapper.writeValueAsString(snapshot) }?.let { Persist.write(KEY, it) }
    }

    private companion object { const val KEY = "brostream_verdicts_v1" }
}

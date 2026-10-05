package com.brostream

import com.brostream.sources.VideoSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PipelineTest {
    private val now = 1_800_000_000_000L

    private class FakeSource(
        override val prefix: String,
        private val items: Map<String, List<ItemData>>,
        private val detail: Map<String, VideoDetails> = emptyMap(),
        private val searchResults: List<ItemData> = emptyList(),
    ) : VideoSource {
        override val id = "SRC$prefix"
        override val label = prefix
        override val shortLabel = prefix
        override val baseUrl = "https://$prefix.example"
        override val reliability = 50
        override val speed = 50
        var detailCalls = 0
        override fun searchPath(query: String) = "/search"
        override suspend fun catalogue(page: Int, path: String) = if (page == 1) items[path].orEmpty() else emptyList()
        override suspend fun search(page: Int, query: String) = searchResults
        override suspend fun details(item: ItemData): VideoDetails? {
            detailCalls++
            return detail[item.url]
        }
        override suspend fun playback(item: ItemData): List<StreamCandidate> = emptyList()
        override suspend fun health() = SourceHealth(id, true, items.size)
    }

    private fun video(source: String, n: Int, title: String, vararg extra: Pair<String, Any>): ItemData {
        var item = ItemData("SRC$source", "https://$source.example/videos/$n/slug/", title)
        extra.forEach { (k, v) ->
            item = when (k) {
                "views" -> item.copy(views = v as Long)
                "at" -> item.copy(uploadedAt = v as Long)
                "tags" -> @Suppress("UNCHECKED_CAST") item.copy(tags = v as List<String>)
                else -> item
            }
        }
        return item
    }

    private fun pipeline(vararg sources: VideoSource, blocklist: Blocklist = Blocklist.EMPTY) = Pipeline(
        sources.toList(), Deduplicator { null }, now = { now }, blocklist = { blocklist }, ownerOf = { null },
    )

    private fun row(feeds: List<Feed>, topic: Topic? = null, sort: SortRule = SortRule.SOURCE_ORDER, window: Long = 0, min: Int = 1,
                    fallback: String = "") = CategoryRow("test|row", "Row", feeds, topic, sort, fallback, window, min)

    @Test fun `female items never reach the screen`() = runBlocking {
        val source = FakeSource("T", mapOf("/" to listOf(
            video("T", 1, "Two guys at the gym"), video("T", 2, "Hot girl and a guy"), video("T", 3, "Lesbian night"),
            video("T", 4, "Bi MMF threesome"),
        )))
        val shown = pipeline(source).loadRow(row(listOf(Feed("T", "/"))), 1)
        assertEquals(listOf("Two guys at the gym"), shown.map { it.title })
    }

    @Test fun `an unclear title is shown only after its detail page proves it`() = runBlocking {
        val clear = video("T", 1, "Hot scene")
        val unclear = video("T", 2, "Another scene")
        val source = FakeSource("T", mapOf("/" to listOf(clear, unclear)), mapOf(
            clear.url to VideoDetails("Hot scene", null, null, tags = listOf("Gay", "Men")),
            unclear.url to VideoDetails("Another scene", null, null, tags = listOf("Outdoor")),
        ))
        val p = pipeline(source)
        val shown = p.loadRow(row(listOf(Feed("T", "/"))), 1)
        assertEquals(listOf("Hot scene"), shown.map { it.title })
        assertTrue(p.quarantine.contains(unclear))
        assertFalse(p.quarantine.contains(clear))
    }

    @Test fun `detail page genders reject an item whose title looked fine`() = runBlocking {
        val item = video("T", 1, "Some scene")
        val source = FakeSource("T", mapOf("/" to listOf(item)), mapOf(
            item.url to VideoDetails("Some scene", null, null, tags = listOf("Gay"), genders = listOf("male", "female")),
        ))
        assertTrue(pipeline(source).loadRow(row(listOf(Feed("T", "/"))), 1).isEmpty())
    }

    @Test fun `inspected unclear videos are not fetched again`() = runBlocking {
        val item = video("T", 1, "Untitled clip")
        val source = FakeSource("T", mapOf("/" to listOf(item)), mapOf(item.url to VideoDetails("x", null, null, emptyList())))
        val p = pipeline(source)
        val r = row(listOf(Feed("T", "/")))
        p.loadRow(r, 1); p.loadRow(r, 1); p.loadRow(r, 1)
        assertEquals(1, source.detailCalls)
    }

    @Test fun `a row with too few verified videos is hidden`() = runBlocking {
        val source = FakeSource("T", mapOf("/" to listOf(video("T", 1, "Two guys"), video("T", 2, "Girl"))))
        assertTrue(pipeline(source).loadRow(row(listOf(Feed("T", "/")), min = 3), 1).isEmpty())
    }

    @Test fun `fallback providers fill a short row`() = runBlocking {
        val primary = FakeSource("A", mapOf("/" to listOf(video("A", 1, "Gay guys one"))))
        val backup = FakeSource("B", emptyMap(), searchResults = listOf(video("B", 2, "Gay men two"), video("B", 3, "Gay men three")))
        val shown = pipeline(primary, backup).loadRow(row(listOf(Feed("A", "/")), min = 3, fallback = "gay"), 1)
        assertEquals(3, shown.size)
    }

    @Test fun `New Today shows only recent uploads newest first`() = runBlocking {
        val day = 86_400_000L
        val source = FakeSource("T", mapOf("/" to listOf(
            video("T", 1, "Gay guys old", "at" to now - 3 * day), video("T", 2, "Gay guys fresh", "at" to now - 3_600_000L),
            video("T", 3, "Gay guys newest", "at" to now - 60_000L), video("T", 4, "Gay guys undated"),
        )))
        val shown = pipeline(source).loadRow(row(listOf(Feed("T", "/")), sort = SortRule.NEWEST, window = day), 1)
        assertEquals(listOf("Gay guys newest", "Gay guys fresh"), shown.map { it.title })
    }

    @Test fun `Popular This Week ranks by views within the window`() = runBlocking {
        val day = 86_400_000L
        val source = FakeSource("T", mapOf("/" to listOf(
            video("T", 1, "Gay guys a", "at" to now - day, "views" to 10L), video("T", 2, "Gay guys b", "at" to now - 2 * day, "views" to 900L),
            video("T", 3, "Gay guys c", "at" to now - 30 * day, "views" to 99999L), video("T", 4, "Gay guys d", "at" to now - day),
        )))
        val shown = pipeline(source).loadRow(row(listOf(Feed("T", "/")), sort = SortRule.MOST_VIEWED, window = 7 * day), 1)
        assertEquals(listOf("Gay guys b", "Gay guys a"), shown.map { it.title })
    }

    @Test fun `a row without timestamps is hidden instead of faking freshness`() = runBlocking {
        val source = FakeSource("T", mapOf("/" to listOf(video("T", 1, "Gay guys"), video("T", 2, "Gay men"))))
        val shown = pipeline(source).loadRow(row(listOf(Feed("T", "/")), sort = SortRule.NEWEST, window = 86_400_000L, min = 1), 1)
        assertTrue(shown.isEmpty())
    }

    @Test fun `amateur results from several providers are combined ranked and deduplicated`() = runBlocking {
        val a = FakeSource("A", mapOf("/" to listOf(
            video("A", 1, "Amateur gay guys beach", "views" to 100L), video("A", 2, "Amateur gay guys sofa", "views" to 5000L),
        )))
        val b = FakeSource("B", mapOf("/" to listOf(
            video("B", 9, "Amateur gay guys beach", "views" to 100L), video("B", 8, "Amateur gay men garden", "views" to 300L),
        )))
        val topic = Topic(any = wordRegex("amateur"))
        val shown = pipeline(a, b).loadRow(row(listOf(Feed("A", "/"), Feed("B", "/")), topic, SortRule.RANKED, min = 3), 1)
        assertEquals(3, shown.size)
        assertEquals("Amateur gay guys sofa", shown.first().title)
        assertEquals(1, shown.count { it.title == "Amateur gay guys beach" })
    }

    @Test fun `blocklist removes videos without a new release`() = runBlocking {
        val source = FakeSource("T", mapOf("/" to listOf(video("T", 1, "Gay guys one"), video("T", 2, "Gay guys two"))))
        val shown = pipeline(source, blocklist = Blocklist(ids = setOf("SRCT:1"))).loadRow(row(listOf(Feed("T", "/"))), 1)
        assertEquals(listOf("Gay guys two"), shown.map { it.title })
    }

    @Test fun `search applies the same policy`() = runBlocking {
        val source = FakeSource("T", emptyMap(), searchResults = listOf(video("T", 1, "Gay guys"), video("T", 2, "Girl and guy")))
        assertEquals(listOf("Gay guys"), pipeline(source).search("x").map { it.title })
    }

    @Test fun `stats record why items were rejected`() = runBlocking {
        val source = FakeSource("T", mapOf("/" to listOf(video("T", 1, "Gay guys"), video("T", 2, "Girl and guy"))))
        val p = pipeline(source)
        p.loadRow(row(listOf(Feed("T", "/"))), 1)
        assertTrue(p.stats.snapshot().keys.any { it.startsWith("REJECT") })
        assertTrue(p.stats.snapshot().keys.any { it.startsWith("ACCEPT") })
    }

    @Test fun `expiring cache drops old entries`() {
        var time = 0L
        val cache = ExpiringCache<String>(max = 2, ttlMillis = 100, now = { time })
        cache.put("a", "1"); cache.put("b", "2"); cache.put("c", "3")
        assertNull(cache.get("a"))
        assertEquals("3", cache.get("c"))
        time = 100
        assertNull(cache.get("c"))
    }
}

package com.epornergay

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CatalogueReliabilityTest {
    private data class Entry(val url: String, val title: String)
    private val first = Entry("https://media.example/videos/1", "Mountain walk")
    private val second = Entry("https://media.example/videos/2", "City tour")
    private fun keys(entry: Entry) = catalogueKeys(entry.url, entry.title)

    @Test fun `tracking links and fragments do not create new entries`() {
        val variants = listOf(first, first.copy(url = "https://MEDIA.example:443/videos/1/?utm_source=test#player"))
        assertEquals(listOf(first), uniqueCatalogue(variants, ::keys))
    }

    @Test fun `query identifiers remain part of an entry identity`() {
        assertNotEquals(catalogueUrlKey("https://media.example/watch?id=1"), catalogueUrlKey("https://media.example/watch?id=2"))
        assertEquals(catalogueUrlKey("https://media.example/watch?id=1&lang=en"), catalogueUrlKey("https://media.example/watch?lang=en&id=1"))
    }

    @Test fun `renamed item with the same host and numeric ID stays one entry`() {
        val renamed = first.copy(url = "https://media.example/videos/1/new-title/", title = "Updated title")
        assertEquals(listOf(first), uniqueCatalogue(listOf(first, renamed), ::keys))
        val otherHost = renamed.copy(url = "https://mirror.example/videos/1/another-title/", title = "Another subject")
        assertEquals(listOf(first, otherHost), uniqueCatalogue(listOf(first, otherHost), ::keys))
    }

    @Test fun `matching titles across hosts are deduplicated`() {
        val mirror = first.copy(url = "https://mirror.example/item/a", title = "Mountain Walk 1080p")
        assertEquals(listOf(first), uniqueCatalogue(listOf(first, mirror), ::keys))
    }

    @Test fun `different non Latin titles remain distinct`() {
        val items = listOf(Entry("https://media.example/a", "山歩き"), Entry("https://media.example/b", "街歩き"))
        assertEquals(items, uniqueCatalogue(items, ::keys))
        assertNotEquals(catalogueTitleKey("山歩き"), catalogueTitleKey("街歩き"))
    }

    @Test fun `long titles are not truncated into one identity`() {
        val prefix = "A scenic walk through the city ".repeat(8)
        assertNotEquals(catalogueTitleKey(prefix + "north"), catalogueTitleKey(prefix + "south"))
    }

    @Test fun `malformed and non network URLs cannot enter catalogue`() {
        for (url in listOf("", "javascript:alert(1)", "file:///tmp/example", "https:///missing-host", "https://bad host/")) {
            assertTrue(keys(Entry(url, "Example")).isEmpty())
            assertFalse(isHttpUrl(url))
        }
    }

    @Test fun `same row cannot repeat entries on a later page`() {
        val claims = CatalogueClaims()
        assertEquals(listOf(first), claims.select("row-a", 1, listOf(first), 60, ::keys))
        assertEquals(listOf(second), claims.select("row-a", 2, listOf(first, second), 60, ::keys))
    }

    @Test fun `same page can be reloaded without disappearing`() {
        val claims = CatalogueClaims()
        repeat(3) { assertEquals(listOf(first), claims.select("row-a", 1, listOf(first, first), 60, ::keys)) }
    }

    @Test fun `refresh releases stale claims including later pages`() {
        val claims = CatalogueClaims()
        claims.select("row-a", 1, listOf(first), 60, ::keys)
        claims.select("row-a", 2, listOf(second), 60, ::keys)
        claims.select("row-a", 1, emptyList<Entry>(), 60, ::keys)
        assertEquals(listOf(first, second), claims.select("row-b", 1, listOf(first, second), 60, ::keys))
    }

    @Test fun `candidates beyond visible limit do not reserve entries`() {
        val claims = CatalogueClaims()
        assertEquals(listOf(first), claims.select("row-a", 1, listOf(first, second), 1, ::keys))
        assertEquals(listOf(second), claims.select("row-b", 1, listOf(second), 60, ::keys))
    }

    @Test fun `fallback decision uses actual primary reservations`() {
        val claims = CatalogueClaims()
        val primaryA = claims.select("row-a", 1, listOf(first), 60, ::keys)
        val primaryB = claims.select("row-b", 1, listOf(first), 60, ::keys)
        assertEquals(listOf(first), primaryA)
        assertTrue(primaryB.isEmpty())
        val fallbackB = if (primaryB.isEmpty()) listOf(second) else emptyList()
        assertEquals(listOf(second), claims.select("row-b", 1, primaryB + fallbackB, 60, ::keys))
        assertEquals(primaryA, claims.select("row-a", 1, primaryA, 60, ::keys))
    }

    @Test fun `rejected title collision leaves no partial URL claim`() {
        val claims = CatalogueClaims()
        claims.select("row-a", 1, listOf(first), 60, ::keys)
        val collision = first.copy(url = "https://mirror.example/2")
        assertTrue(claims.select("row-b", 1, listOf(collision), 60, ::keys).isEmpty())
        val unrelated = collision.copy(title = "Different subject")
        assertEquals(listOf(unrelated), claims.select("row-c", 1, listOf(unrelated), 60, ::keys))
    }

    @Test fun `two simultaneous rows cannot claim the same entry`() {
        val claims = CatalogueClaims()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<List<Entry>>())
        val workers = listOf("row-a", "row-b").map { row ->
            thread {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                results.add(claims.select(row, 1, listOf(first), 60, ::keys))
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(2, results.size)
        assertEquals(1, results.sumOf { it.size })
    }

    @Test fun `abandoned claims expire`() {
        var time = 0L
        val claims = CatalogueClaims(now = { time }, ttlMillis = 100)
        claims.select("row-a", 1, listOf(first), 60, ::keys)
        assertTrue(claims.select("row-b", 1, listOf(first), 60, ::keys).isEmpty())
        time = 100
        assertEquals(listOf(first), claims.select("row-b", 1, listOf(first), 60, ::keys))
    }

    @Test fun `claim storage is bounded`() {
        val claims = CatalogueClaims(maxKeys = 2)
        claims.select("row-a", 1, listOf(first), 60, ::keys)
        claims.select("row-b", 1, listOf(second), 60, ::keys)
        assertEquals(listOf(first), claims.select("row-c", 1, listOf(first), 60, ::keys))
    }

    @Test fun `no callback means resolver failure`() {
        val deliveries = LinkDeliveries()
        attempt { Unit }
        assertFalse(deliveries.hasResults())
    }

    @Test fun `invalid and repeated URLs are not delivered`() {
        val deliveries = LinkDeliveries()
        var calls = 0
        deliveries.emit("javascript:example") { calls++ }
        repeat(3) { deliveries.emit("https://media.example/sample.mp4?token=one") { calls++ } }
        assertEquals(1, calls)
        assertTrue(deliveries.hasResults())
    }

    @Test fun `signed playback URLs retain their query strings`() {
        val deliveries = LinkDeliveries()
        val seen = mutableListOf<String>()
        val urls = listOf("https://media.example/sample.mp4?token=one", "https://media.example/sample.mp4?token=two")
        urls.forEach { url -> deliveries.emit(url) { seen.add(url) } }
        assertEquals(urls, seen)
    }

    @Test fun `failed callback does not count as success`() {
        val deliveries = LinkDeliveries()
        attempt { deliveries.emit("https://media.example/sample.mp4") { throw IOException("delivery failed") } }
        assertFalse(deliveries.hasResults())
    }

    @Test fun `ordinary failures are handled while cancellation propagates`() {
        assertNull(attempt<Unit> { throw IOException("timeout") })
        try {
            attempt<Unit> { throw CancellationException("closed") }
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) {
            // Expected: cancelling the screen also cancels its work.
        }
    }
}

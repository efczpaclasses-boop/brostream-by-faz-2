package com.brostreamah

import com.brostreamah.sources.GayPornTubeSource
import com.brostreamah.sources.GayVidsSource
import com.brostreamah.sources.ManPornSource
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real pipeline against the live sites. Skipped unless Gradle is started with -Plive=true; it writes
 * build/live-report.txt (never fails on content, so a report always exists).
 */
class LiveCatalogueTest {
    @Test fun `report what every row shows`() {
        assumeTrue(System.getProperty("live").orEmpty().isNotBlank())
        // Outside Android CloudStream's own client cannot start, so plain HTTP stands in for it.
        com.brostreamah.sources.Web.fetch = { url, headers, maxBytes ->
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.connectTimeout = 20000; c.readTimeout = 25000
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.let { com.brostreamah.sources.Web.readUpTo(it, maxBytes) } ?: ByteArray(0)
                com.brostreamah.sources.Web.Raw(code, c.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }, body)
            } catch (e: Exception) { null }
        }
        val out = StringBuilder()
        val sources = listOf(ManPornSource(), GayVidsSource(), GayPornTubeSource())
        val pipeline = Pipeline(sources, Deduplicator { id -> sources.firstOrNull { it.id == id }?.let { SourceProfile(it.reliability, it.speed) } })
        runBlocking {
            for (row in Rows.all) {
                val started = System.currentTimeMillis()
                val shown = try { pipeline.loadRow(row, 1) } catch (e: Throwable) { out.appendLine("ERROR ${row.title}: $e"); continue }
                out.appendLine("${if (shown.size >= row.minItems) "OK  " else "HIDE"} ${row.title}: ${shown.size} shown (min ${row.minItems}) in ${System.currentTimeMillis() - started} ms")
                shown.take(3).forEach { out.appendLine("       - [${it.source}] ${it.title.take(80)}") }
                File("build/live-report.txt").writeText(out.toString())
            }
            out.appendLine("\nPOLICY COUNTS"); pipeline.stats.snapshot().forEach { (k, v) -> out.appendLine("  $k = $v") }
            out.appendLine("QUARANTINED: ${pipeline.quarantine.snapshot().size}")
            pipeline.quarantine.snapshot().take(12).forEach { out.appendLine("  - ${it.item.title.take(70)} (${it.reason})") }
        }
        File("build/live-report.txt").writeText(out.toString())
    }
}

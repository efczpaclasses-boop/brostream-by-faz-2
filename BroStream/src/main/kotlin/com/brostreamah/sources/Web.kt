package com.brostreamah.sources

import com.brostreamah.StreamCheck
import com.brostreamah.StreamValidator
import com.brostreamah.attempt
import com.brostreamah.isHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

internal object Web {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
    val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml")

    class Response(val code: Int, val text: String)

    /** A raw HTTP answer: status, response headers (any case) and at most the requested number of body bytes. */
    class Raw(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    /**
     * The one place that touches the network. The host (CloudStream plugin, Android TV app, or the live test)
     * installs it; reading must stop after `maxBytes` of the body.
     */
    var fetch: suspend (url: String, headers: Map<String, String>, maxBytes: Int) -> Raw? = { _, _, _ -> null }

    fun readUpTo(stream: java.io.InputStream, maxBytes: Int): ByteArray = stream.use {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() < maxBytes) {
            val n = it.read(chunk, 0, minOf(chunk.size, maxBytes - buffer.size()))
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        buffer.toByteArray()
    }

    suspend fun page(url: String): Response? =
        attempt { fetch(url, headers, 6_000_000) }?.let { Response(it.code, String(it.body, Charsets.UTF_8)) }

    suspend fun document(url: String): Document? =
        page(url)?.takeIf { it.code in 200..299 }?.let { Jsoup.parse(it.text, url) }

    fun absolute(value: String?, base: String): String? =
        if (value.isNullOrBlank()) null
        else attempt { URI(base).resolve(value).toString() }?.takeIf(::isHttpUrl)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun slug(value: String): String = value.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Requests the first bytes of a stream and judges them; never throws. */
    suspend fun probe(url: String, referer: String, hls: Boolean): StreamCheck = try {
        val raw = fetch(
            url,
            mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Range" to "bytes=0-${StreamValidator.PROBE_BYTES - 1}",
                "Accept-Encoding" to "identity"),
            StreamValidator.PROBE_BYTES,
        )
        if (raw == null) StreamCheck(false, false, "no response") else StreamValidator.check(raw.code, raw.headers, raw.body, hls)
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        StreamCheck(false, false, e.javaClass.simpleName)
    }
}

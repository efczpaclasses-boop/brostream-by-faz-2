package com.brostreamah.sources

import com.brostreamah.StreamCheck
import com.brostreamah.StreamValidator
import com.brostreamah.attempt
import com.brostreamah.isHttpUrl
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

internal object Web {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
    val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml")

    class Response(val code: Int, val text: String)

    /** Fetches a page. Replaced only by the opt-in live test, which runs outside Android. */
    var transport: suspend (String) -> Response? = { url ->
        attempt { app.get(url, headers = headers, timeout = 25).let { Response(it.code, it.text) } }
    }

    suspend fun page(url: String): Response? = transport(url)

    suspend fun document(url: String): Document? =
        page(url)?.takeIf { it.code in 200..299 }?.let { Jsoup.parse(it.text, url) }

    fun absolute(value: String?, base: String): String? =
        if (value.isNullOrBlank()) null
        else attempt { URI(base).resolve(value).toString() }?.takeIf(::isHttpUrl)

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun slug(value: String): String = value.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Requests the first bytes of a stream and judges them; never throws. */
    suspend fun probe(url: String, referer: String, hls: Boolean): StreamCheck = try {
        val response = app.get(
            url, timeout = 20,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer, "Range" to "bytes=0-${StreamValidator.PROBE_BYTES - 1}",
                "Accept-Encoding" to "identity"),
        )
        val body = response.body.byteStream().use { stream ->
            val buffer = ByteArray(StreamValidator.PROBE_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        StreamValidator.check(response.code, response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }, body, hls)
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        StreamCheck(false, false, e.javaClass.simpleName)
    }
}

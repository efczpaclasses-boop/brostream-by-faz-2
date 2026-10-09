package com.brostreamah

import com.brostreamah.sources.Web
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.util.Locale

/** Finds stream URLs on a watch page, most trustworthy source first. */
internal object StreamExtractor {
    private val embeddedMp4 = Regex("https?[^\\\"'\\s<>]+?\\.mp4[^\\\"'\\s<>]*", RegexOption.IGNORE_CASE)
    private val embeddedHls = Regex("https?[^\\\"'\\s<>]+?\\.m3u8[^\\\"'\\s<>]*", RegexOption.IGNORE_CASE)

    /**
     * KVS-style players list URLs as `video_url: '...'`, with an optional `video_url_text: '480p'` label and
     * `video_alt_url`, `video_alt_url2`... for other qualities. Some sites prefix the URL with `function/0/`.
     */
    fun flashvars(html: String): List<Pair<String, Int>> =
        Regex("""(video_(?:alt_)?url\d*)\s*:\s*['"]([^'"]+)['"]""").findAll(html).mapNotNull { match ->
            val key = match.groupValues[1]
            val url = clean(match.groupValues[2]).replace(Regex("^function/\\d+/"), "")
            if (!isHttpUrl(url)) return@mapNotNull null
            val label = Regex(key + """_text\s*:\s*['"]([^'"]*)['"]""").find(html)?.groupValues?.get(1)
            url to qualityOf(label, url)
        }.toList()

    /** "&amp;" and "&" show up in expiring tokens; a literal "&amp;" in a URL makes the signature fail. */
    fun clean(url: String): String =
        Parser.unescapeEntities(url, false)
            .replace("\\u0026", "&").replace("\\u002F", "/").replace("\\/", "/").trim()

    /** 2160 for "4k", otherwise the vertical resolution, or 0 when nothing says. */
    fun qualityOf(vararg hints: String?): Int {
        for (hint in hints) {
            val text = hint?.lowercase(Locale.ROOT).orEmpty()
            if (text.isEmpty()) continue
            if (Regex("(?<![\\p{L}\\p{N}])(4k|uhd)(?![\\p{L}\\p{N}])").containsMatchIn(text)) return 2160
            Regex("(?<!\\d)(2160|1440|1080|720|576|540|480|360|240)\\s*p?(?!\\d)").find(text)?.let { return it.groupValues[1].toInt() }
        }
        return 0
    }

    fun label(quality: Int): String = when {
        quality >= 2160 -> "4K"
        quality > 0 -> "${quality}p"
        else -> "Auto"
    }

    /**
     * Declared <source>/<video> elements and structured data are used first; the page-wide regex runs
     * only when they found nothing. Identical qualities collapse to the first URL seen.
     */
    fun extract(doc: Document, html: String, pageUrl: String, structured: List<String> = emptyList()): List<StreamCandidate> {
        val declared = doc.select("video source[src], source[src][type*=video], source[src][type*=mpegurl], video[src]").mapNotNull { el ->
            Web.absolute(clean(el.attr("src")), pageUrl)?.let { url ->
                url to qualityOf(el.attr("size"), el.attr("res"), el.attr("data-res"), el.attr("label"), el.attr("title"), url)
            }
        }
        val meta = (doc.select("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[itemprop=contentUrl]")
            .map { it.attr("content") } + structured).mapNotNull { Web.absolute(clean(it), pageUrl) }
            .filter { it.contains(".mp4", true) || it.contains(".m3u8", true) }
            .map { it to qualityOf(it) }
        var found = declared + meta + flashvars(html)
        if (found.isEmpty()) {
            val text = html
            found = (embeddedMp4.findAll(text) + embeddedHls.findAll(text)).map { clean(it.value) }
                .filterNot { it.endsWith(".mp4.jpg", true) || it.contains(".mp4.jpg?", true) }
                .map { it to qualityOf(it) }.toList()
        }
        val seen = HashSet<String>()
        return found.filter { (url, quality) ->
            val identity = if (quality > 0) "q$quality" else "u${url.substringBefore('?')}"
            isHttpUrl(url) && seen.add(identity)
        }.map { (url, quality) -> StreamCandidate(url, quality, url.substringBefore('?').endsWith(".m3u8", true)) }
    }
}

/** Result of testing a stream URL with a small request before it is offered to the player. */
data class StreamCheck(val playable: Boolean, val rangeable: Boolean, val reason: String)

internal object StreamValidator {
    const val PROBE_BYTES = 1024
    private val mediaTypes = setOf(
        "video/mp4", "application/mp4", "video/x-m4v", "video/quicktime", "video/webm",
        "application/octet-stream", "binary/octet-stream", "video/mpeg",
    )
    private val hlsTypes = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")

    fun isIsoBmff(body: ByteArray): Boolean {
        if (body.size < 16 || String(body, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
        val size = ((body[0].toLong() and 0xff) shl 24) or ((body[1].toLong() and 0xff) shl 16) or
            ((body[2].toLong() and 0xff) shl 8) or (body[3].toLong() and 0xff)
        return size >= 16 && (size <= body.size || size > PROBE_BYTES)
    }

    fun isWebm(body: ByteArray) =
        body.size >= 4 && body[0] == 0x1a.toByte() && body[1] == 0x45.toByte() && body[2] == 0xdf.toByte() && body[3] == 0xa3.toByte()

    /**
     * Judges a response to "Range: bytes=0-1023". [headers] keys are matched case-insensitively. A server that
     * ignores Range (HTTP 200) is still playable but not rangeable, which hurts seeking on Chromecast.
     */
    fun check(status: Int, headers: Map<String, String>, body: ByteArray, expectHls: Boolean): StreamCheck {
        val fields = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value.trim() }
        if (status !in 200..299) return StreamCheck(false, false, "HTTP $status")
        val type = fields["content-type"].orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (type.startsWith("text/html")) return StreamCheck(false, false, "HTML page instead of media")
        if (body.isEmpty()) return StreamCheck(false, false, "empty body")
        if (expectHls) {
            val head = String(body, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r')
            return if (head.startsWith("#EXTM3U")) StreamCheck(true, true, "HLS playlist")
            else StreamCheck(false, false, "not an HLS playlist (type ${type.ifEmpty { "missing" }})")
        }
        if (type !in mediaTypes && type !in hlsTypes && !type.startsWith("video/"))
            return StreamCheck(false, false, "not a media type: ${type.ifEmpty { "missing" }}")
        if (!isIsoBmff(body) && !isWebm(body)) return StreamCheck(false, false, "no MP4 or WebM signature")
        val contentRange = fields["content-range"]
        val rangeable = status == 206 && (contentRange == null || Regex("bytes 0-\\d+/(\\d+|\\*)", RegexOption.IGNORE_CASE).matches(contentRange)) ||
            fields["accept-ranges"].equals("bytes", ignoreCase = true)
        return StreamCheck(true, rangeable, if (rangeable) "seekable" else "server ignores byte ranges")
    }

    /** Order offered to the player: adaptive or seekable first, then higher resolution. */
    fun order(candidates: List<Pair<StreamCandidate, StreamCheck>>): List<Pair<StreamCandidate, StreamCheck>> =
        candidates.filter { it.second.playable }
            .sortedWith(compareByDescending<Pair<StreamCandidate, StreamCheck>> { it.first.isHls || it.second.rangeable }
                .thenByDescending { it.first.quality })
}

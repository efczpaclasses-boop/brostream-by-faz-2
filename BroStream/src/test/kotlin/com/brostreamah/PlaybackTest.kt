package com.brostreamah

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PlaybackTest {
    private fun extract(html: String, structured: List<String> = emptyList()) =
        StreamExtractor.extract(Jsoup.parse(html, "https://site.example/videos/1/x/"), html, "https://site.example/videos/1/x/", structured)

    @Test fun `entities in signed URLs are decoded`() {
        assertEquals("https://c.example/a.mp4?token=1&expires=2", StreamExtractor.clean("https://c.example/a.mp4?token=1&amp;expires=2"))
        assertEquals("https://c.example/a.mp4?t=1&e=2", StreamExtractor.clean("https://c.example/a.mp4?t=1\\u0026e=2"))
        assertEquals("https://c.example/a.mp4", StreamExtractor.clean("https:\\/\\/c.example\\/a.mp4"))
    }

    @Test fun `declared sources are used before the broad regex`() {
        val html = """<video><source src="https://c.example/a_720.mp4?t=1&amp;e=2" type="video/mp4"></video>
            <script>var u="https://other.example/zzz_1080.mp4";</script>"""
        val streams = extract(html)
        assertEquals(listOf("https://c.example/a_720.mp4?t=1&e=2"), streams.map { it.url })
        assertEquals(720, streams.single().quality)
    }

    @Test fun `structured data is used before the regex`() {
        val html = """<script>var u="https://other.example/zzz_1080.mp4";</script>"""
        val streams = extract(html, listOf("https://cdn.example/clip_480.mp4"))
        assertEquals(listOf("https://cdn.example/clip_480.mp4"), streams.map { it.url })
    }

    @Test fun `regex is the last resort and skips thumbnails`() {
        val html = """<script>var a="https://c.example/v_1080.mp4"; var t="https://c.example/v.mp4.jpg";</script>"""
        assertEquals(listOf("https://c.example/v_1080.mp4"), extract(html).map { it.url })
    }

    @Test fun `duplicate qualities collapse and labels are accurate`() {
        val html = """<video>
            <source src="https://a.example/x_720.mp4"><source src="https://b.example/y_720.mp4">
            <source src="https://a.example/x_1080.mp4"><source src="https://a.example/x_2160.mp4">
            <source src="https://a.example/x_480.mp4" size="480"><source src="https://a.example/plain.mp4">
            <source src="https://a.example/plain.mp4?again=1"></video>"""
        val streams = extract(html)
        assertEquals(listOf(720, 1080, 2160, 480, 0), streams.map { it.quality })
        assertEquals(listOf("720p", "1080p", "4K", "480p", "Auto"), streams.map { StreamExtractor.label(it.quality) })
    }

    @Test fun `quality hints are read from attributes and names`() {
        assertEquals(2160, StreamExtractor.qualityOf("4K"))
        assertEquals(1080, StreamExtractor.qualityOf(null, "1080p"))
        assertEquals(720, StreamExtractor.qualityOf("https://c.example/clip_720p.mp4"))
        assertEquals(0, StreamExtractor.qualityOf("https://c.example/clip.mp4"))
        assertEquals(0, StreamExtractor.qualityOf("https://c.example/video12345678.mp4"))
    }

    @Test fun `player flashvars give urls with labels and skip the page-wide regex`() {
        val html = """<script>var f = { video_url: 'https://c.example/get_file/1/90077.mp4/?tok=a', video_url_text: '480p',
            video_alt_url: 'function/0/https://c.example/get_file/2/90077_hd.mp4/?tok=b', video_alt_url_text: '1080p' };
            var pixel="https://c.example/get_file/9/pixel.mp4/";</script>"""
        val streams = extract(html)
        assertEquals(listOf(480, 1080), streams.map { it.quality })
        assertEquals("https://c.example/get_file/2/90077_hd.mp4/?tok=b", streams[1].url)
        assertEquals(2, streams.size)
    }

    @Test fun `hls is recognised`() {
        val streams = extract("""<video><source src="https://c.example/master.m3u8" type="application/x-mpegURL"></video>""")
        assertTrue(streams.single().isHls)
    }

    private fun mp4(): ByteArray = ByteArray(64).also {
        it[3] = 24; "ftypisom".toByteArray().copyInto(it, 4)
    }

    @Test fun `a real MP4 range response is playable and seekable`() {
        val check = StreamValidator.check(206, mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-1023/5000"), mp4(), false)
        assertTrue(check.playable); assertTrue(check.rangeable)
    }

    @Test fun `a server that ignores range is playable but not seekable`() {
        val check = StreamValidator.check(200, mapOf("content-type" to "video/mp4"), mp4(), false)
        assertTrue(check.playable); assertFalse(check.rangeable)
        assertTrue(StreamValidator.check(200, mapOf("content-type" to "video/mp4", "accept-ranges" to "bytes"), mp4(), false).rangeable)
    }

    @Test fun `html error pages and expired links are not playable`() {
        assertFalse(StreamValidator.check(200, mapOf("content-type" to "text/html"), "<html>".toByteArray(), false).playable)
        assertFalse(StreamValidator.check(403, mapOf("content-type" to "video/mp4"), mp4(), false).playable)
        assertFalse(StreamValidator.check(200, mapOf("content-type" to "video/mp4"), ByteArray(0), false).playable)
        assertFalse(StreamValidator.check(200, mapOf("content-type" to "video/mp4"), "plain text not a video file".toByteArray(), false).playable)
    }

    @Test fun `hls needs a playlist header`() {
        assertTrue(StreamValidator.check(200, mapOf("content-type" to "application/vnd.apple.mpegurl"), "#EXTM3U\n#EXT-X".toByteArray(), true).playable)
        assertFalse(StreamValidator.check(200, mapOf("content-type" to "text/plain"), "<html>".toByteArray(), true).playable)
    }

    @Test fun `seekable or adaptive streams are offered first then higher quality`() {
        val seekable = StreamCheck(true, true, ""); val fixed = StreamCheck(true, false, ""); val bad = StreamCheck(false, false, "x")
        val ordered = StreamValidator.order(listOf(
            StreamCandidate("a", 1080, false) to fixed, StreamCandidate("b", 480, false) to seekable,
            StreamCandidate("c", 720, false) to seekable, StreamCandidate("d", 2160, false) to bad,
            StreamCandidate("e", 0, true) to seekable,
        ))
        assertEquals(listOf("c", "b", "e", "a"), ordered.map { it.first.url })
        assertEquals("a", ordered.last().first.url)
        assertFalse(ordered.any { it.first.url == "d" })
    }
}

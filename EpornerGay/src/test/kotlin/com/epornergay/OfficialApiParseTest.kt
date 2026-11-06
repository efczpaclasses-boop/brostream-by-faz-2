package com.epornergay

import org.junit.Assert.*
import org.junit.Test

class OfficialApiParseTest {
    @Test fun `chaturbate keeps the campaign, male gender, hd, region and at most five tags`() {
        val plain = ChaturbateMaleLive.apiUrl(1, ChaturbateMaleLive.Query())
        assertTrue(plain.contains("wm=0esJu"))
        assertTrue(plain.contains("gender=m"))
        assertFalse(plain.contains("hd=true"))
        val filtered = ChaturbateMaleLive.apiUrl(
            2,
            ChaturbateMaleLive.Query(hd = true, region = "asia", tags = listOf("a", "b", "c", "d", "e", "dropped")),
        )
        assertTrue(filtered.contains("hd=true"))
        assertTrue(filtered.contains("region=asia"))
        assertTrue(filtered.contains("tag=a"))
        assertTrue(filtered.contains("tag=e"))
        assertFalse(filtered.contains("dropped"))
        assertTrue(filtered.contains("offset=40"))
    }

    @Test fun `chaturbate orders hd before viewer count and drops other genders and minors`() {
        val body = """
            {"count":4,"results":[
              {"username":"low","gender":"m","age":30,"num_users":10,"is_hd":false,"current_show":"public"},
              {"username":"busy","gender":"m","age":25,"num_users":5,"is_hd":true,"current_show":"public"},
              {"username":"other","gender":"f","age":24,"num_users":99,"is_hd":true,"current_show":"public"},
              {"username":"minor","gender":"m","age":17,"num_users":50,"is_hd":true,"current_show":"public"}
            ]}
        """.trimIndent()
        val rooms = ChaturbateMaleLive.present(ChaturbateMaleLive.parseRooms(body))
        assertEquals(listOf("busy", "low"), rooms.map { it.username })
        val embed = officialEmbedSrc("<iframe src='https://chaturbate.com/in/?tour=x&amp;campaign=0esJu&amp;room=busy'></iframe>")
        assertEquals("https://chaturbate.com/in/?tour=x&campaign=0esJu&room=busy", embed)
    }

    @Test fun `stripchat requests men rather than gender and keeps returned playlists`() {
        val men = StripchatMaleLive.apiUrl(1, StripchatMaleLive.Query())
        assertTrue(men.contains("tag=men"))
        assertTrue(men.contains("sortBy=viewersCount"))
        assertFalse(men.contains("gender="))
        assertFalse(men.contains("userId="))
        val hd = StripchatMaleLive.apiUrl(1, StripchatMaleLive.Query(hd = true), affiliateId = "aff")
        assertTrue(hd.contains("broadcastHD=true"))
        assertTrue(hd.contains("userId=aff"))
        assertTrue(StripchatMaleLive.apiUrl(1, StripchatMaleLive.Query(tag = "bears")).contains("tag=men%2Fbears"))
        val body = """
            {"models":[
              {"id":2,"username":"sd","gender":"males","broadcastHD":false,"viewersCount":40,"status":"public",
                "tags":["men","men/bears"],"stream":{"url":"https://cdn.example/sd.m3u8","urls":{"240p":"https://cdn.example/240.m3u8","720p":"https://cdn.example/720.m3u8"}}},
              {"id":1,"username":"hd","gender":"male","broadcastHD":true,"viewersCount":9,"status":"public",
                "tags":["men"],"stream":{"url":"https://cdn.example/hd.m3u8","urls":{"480p":"https://cdn.example/hd480.m3u8"}}},
              {"id":3,"username":"couple","gender":"maleFemale","broadcastHD":true,"viewersCount":100,"status":"public","tags":["men"]},
              {"id":4,"username":"priv","gender":"male","broadcastHD":true,"viewersCount":80,"status":"private","tags":["men"]}
            ]}
        """.trimIndent()
        val online = mapOf("2" to "public", "1" to "public", "3" to "public", "4" to "private")
        val shown = StripchatMaleLive.present(StripchatMaleLive.parseModels(body), online, StripchatMaleLive.Query())
        assertEquals(listOf("hd", "sd"), shown.map { it.username })
        assertEquals(
            listOf("https://cdn.example/720.m3u8", "https://cdn.example/240.m3u8", "https://cdn.example/sd.m3u8"),
            StripchatMaleLive.streamUrls(shown.first { it.username == "sd" }),
        )
        val bears = StripchatMaleLive.present(StripchatMaleLive.parseModels(body), online, StripchatMaleLive.Query(tag = "bears"))
        assertEquals(listOf("sd"), bears.map { it.username })
    }

    @Test fun `camsoda stays hidden until the affiliate id is set and uses returned links`() {
        assertTrue(CamSodaMaleLive.rows("").isEmpty())
        assertFalse(CamSodaMaleLive.rows("aff").any { it.id == CamSodaMaleLive.NOTE_ROW })
        val url = CamSodaMaleLive.apiUrl(1, "muscular", "aff")
        assertTrue(url.contains("id=aff"))
        assertTrue(url.contains("gender=m"))
        assertTrue(url.contains("tag=muscular"))
        val body = """
            {"results":[
              {"username":"a","name":"A","gender":"m","viewers":3,"tags":["hd"],"link_iframe":"https://www.camsoda.com/embed/a","livestream_hls":"https://cdn.example/a.m3u8"},
              {"username":"b","name":"B","gender":"f","viewers":9,"tags":["hd"],"link_iframe":"https://www.camsoda.com/embed/b","livestream_hls":"https://cdn.example/b.m3u8"},
              {"username":"c","name":"C","gender":"m","viewers":8,"tags":["daddy"],"link_iframe":"https://www.camsoda.com/embed/c","livestream_hls":"https://cdn.example/c.m3u8"}
            ]}
        """.trimIndent()
        val men = CamSodaMaleLive.present(CamSodaMaleLive.parse(body), null)
        assertEquals(listOf("a", "c"), men.map { it.username })
        assertEquals("https://cdn.example/a.m3u8", men.first().livestreamHls)
        assertEquals("https://www.camsoda.com/embed/a", men.first().linkIframe)
        assertEquals(listOf("c"), CamSodaMaleLive.present(CamSodaMaleLive.parse(body), "daddy").map { it.username })
    }

    @Test fun `eporner search uses the gay api and drops removed ids`() {
        val url = EpornerGayVideos.apiUrl(2, "top-weekly", "amateur")
        assertTrue(url.contains("gay=2"))
        assertTrue(url.contains("lq=0"))
        assertTrue(url.contains("order=top-weekly"))
        assertTrue(url.contains("query=amateur"))
        assertTrue(EpornerGayVideos.apiUrl(1, "latest", "all").contains("query=gay"))
        assertFalse(EpornerGayVideos.apiUrl(1, "latest", "all").contains("query=all"))
        assertFalse(EpornerGayVideos.gayMaleTitle("BBW Shemale Fucked"))
        assertTrue(EpornerGayVideos.gayMaleTitle("Pinoy Gay Sex In Hotel"))
        val body = """
            {"total_pages":3,"videos":[
              {"id":"keep0000001","title":"Kept","views":10,"embed":"https://www.eporner.com/embed/keep0000001/","url":"https://www.eporner.com/hd-porn/keep0000001/Kept/","default_thumb":{"src":"https://cdn.example/a.jpg"}},
              {"id":"drop0000001","title":"Gone","views":99,"embed":"https://www.eporner.com/embed/drop0000001/","url":"https://www.eporner.com/hd-porn/drop0000001/Gone/"}
            ]}
        """.trimIndent()
        val parsed = EpornerGayVideos.parseSearch(body)
        assertEquals("https://www.eporner.com/embed/keep0000001/", EpornerGayVideos.embedUrl(parsed.videos.first()))
        val removed = EpornerGayVideos.removedIds("""[{"id":"drop0000001"},{"id":"other000001"}]""")
        assertEquals(setOf("drop0000001", "other000001"), removed)
        assertEquals(listOf("keep0000001"), EpornerGayVideos.withoutRemoved(parsed.videos, removed).map { it.id })
        assertEquals(setOf("one", "two"), EpornerGayVideos.removedIds("one\n\ntwo\n"))
        val bloom = RemovedIdBloom(bits = 1024)
        bloom.add("drop0000001")
        assertTrue(bloom.mightContain("drop0000001"))
        assertFalse(bloom.mightContain("keep0000001"))
        assertEquals(4.5, epornerRate(com.fasterxml.jackson.databind.node.TextNode("4.50")), 0.001)
    }

    @Test fun `pornhub keeps gay segment results and embed codes from the webmasters api`() {
        val categories = PornhubGayWebmasters.gayCategorySlugs(
            """{"categories":[{"id":"1","category":"amateur"},{"id":"2","category":"amateur-gay"},{"id":"3","category":"bear-gay"}]}""",
        )
        assertEquals(listOf("amateur-gay", "bear-gay"), categories)
        assertEquals(42, PornhubGayWebmasters.GAY_CATEGORIES.size)
        assertTrue(PornhubGayWebmasters.GAY_CATEGORIES.all { it.endsWith("-gay") })
        val videos = PornhubGayWebmasters.parseSearch(
            """{"videos":[
              {"title":"A","video_id":"g1","segment":"gay","views":"12","url":"https://www.pornhub.com/view_video.php?viewkey=g1"},
              {"title":"B","video_id":"s1","segment":"straight","views":99,"url":"https://www.pornhub.com/view_video.php?viewkey=s1"},
              {"title":"C","video_id":"g2","segment":"gay","views":40,"url":"https://www.pornhub.com/view_video.php?viewkey=g2"}
            ]}""",
        )
        assertEquals(listOf("g2", "g1"), PornhubGayWebmasters.gayOnly(videos).map { it.videoId })
        val embed = PornhubGayWebmasters.embedSrc(
            """{"embed":{"code":"<iframe src=\"https://www.pornhub.com/embed/g1\"></iframe>"}}""",
        )
        assertEquals("https://www.pornhub.com/embed/g1", embed)
        assertTrue(PornhubGayWebmasters.searchUrl(1, "amateur-gay", null).contains("category=amateur-gay"))
    }

    @Test fun `settings keep core rows and can hide optional categories`() {
        val rows = OfficialCatalogue.allRows()
        val defaults = OfficialCatalogue.rowPairs(null).map { it.first }
        assertEquals(
            listOf(
                ChaturbateMaleLive.ROW_KEY,
                "CB|hd",
                "SC|men",
                "SC|hd",
                "EP|top-weekly|all",
                "EP|most-popular|all",
                "EP|latest|all",
                "EP|top-rated|all",
            ),
            defaults,
        )
        assertFalse(defaults.contains(CamSodaMaleLive.NOTE_ROW))
        assertFalse(defaults.any { it.startsWith("PH|") || it.startsWith("CB|tag|") || it.startsWith("SC|tag|") })
        val coreOnly = OfficialCatalogue.rowPairs(emptySet()).map { it.first }
        assertTrue(coreOnly.contains(ChaturbateMaleLive.ROW_KEY))
        assertFalse(coreOnly.contains("PH|amateur-gay"))
        assertFalse(coreOnly.any { it.startsWith("CB|tags|") })
        val mixed = OfficialCatalogue.rowPairs(setOf("CB|tag|muscle", "CB|tag|bear")).map { it.first }
        assertTrue(mixed.contains("CB|tags|muscle,bear"))
        assertTrue(rows.any { it.id == "CB|region|asia" })
    }

    @Test fun `hd is ordered ahead of a higher viewer count`() {
        data class Row(val hd: Boolean, val viewers: Long)
        val ordered = listOf(Row(false, 50), Row(true, 1), Row(false, 9), Row(true, 3))
            .orderHdThenPopularity({ it.hd }, { it.viewers })
        assertEquals(listOf(3L, 1L, 50L, 9L), ordered.map { it.viewers })
    }
}

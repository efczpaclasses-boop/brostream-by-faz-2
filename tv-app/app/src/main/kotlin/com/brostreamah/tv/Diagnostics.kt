package com.brostreamah.tv

import com.brostreamah.ContentPolicy
import com.brostreamah.ItemData
import com.brostreamah.Verdict
import com.brostreamah.sources.GayPornTubeSource
import com.brostreamah.sources.GayVidsSource
import com.brostreamah.sources.ManPornSource
import com.brostreamah.sources.Web

/** A quick self-check shown in Settings, so "nothing loads" can be traced to the network or the app. */
internal object Diagnostics {
    suspend fun run(): List<String> {
        val lines = mutableListOf<String>()
        lines += try {
            val cases = listOf(
                "Two guys at the gym" to Verdict.ACCEPT, "Hot girl with big tits" to Verdict.REJECT,
                "Boy after school" to Verdict.REJECT, "Sunny afternoon" to Verdict.AMBIGUOUS,
            )
            val wrong = cases.filter { (title, expected) ->
                ContentPolicy.classify(ItemData("T", "https://t.example/videos/1/x/", title)).verdict != expected
            }
            if (wrong.isEmpty()) "Content filter: OK" else "Content filter: WRONG for ${wrong.map { it.first }}"
        } catch (e: Throwable) {
            "Content filter: FAILED ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}"
        }
        for ((name, url) in listOf(
            "ManPorn" to ManPornSource.BASE_URL, "GayVids" to GayVidsSource.BASE_URL, "GayPornTube" to GayPornTubeSource.BASE_URL,
        )) {
            val started = System.currentTimeMillis()
            val raw = try { Web.fetch(url, Web.headers, 2000) } catch (e: Throwable) { null }
            val ms = System.currentTimeMillis() - started
            lines += when {
                raw == null -> "$name: no connection (${ms} ms)"
                raw.code in 200..299 -> "$name: reachable (HTTP ${raw.code}, ${ms} ms)"
                else -> "$name: answered HTTP ${raw.code} (${ms} ms)"
            }
        }
        return lines
    }
}

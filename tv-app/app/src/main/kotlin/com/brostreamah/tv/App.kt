package com.brostreamah.tv

import android.app.Application
import com.brostreamah.Persist
import com.brostreamah.sources.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class App : Application() {
    internal lateinit var engine: Engine
        private set
    internal lateinit var home: HomeModel
        private set

    override fun onCreate() {
        super.onCreate()
        Persist.context = applicationContext
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        // The shared scraping code does all its network work through this one function.
        Web.fetch = { url, headers, maxBytes ->
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.byteStream()?.let { Web.readUpTo(it, maxBytes) } ?: ByteArray(0)
                        Web.Raw(response.code, response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }, body)
                    }
                } catch (e: java.io.IOException) {
                    null
                }
            }
        }
        engine = Engine(this)
        home = HomeModel(engine)
    }
}

package com.brostreamah

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BroStreamPlugin : Plugin() {
    override fun load(context: Context) {
        Persist.context = context.applicationContext ?: context
        registerMainAPI(BroStreamProvider())
    }
}

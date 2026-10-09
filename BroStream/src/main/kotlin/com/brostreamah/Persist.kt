package com.brostreamah

import android.content.Context
import java.util.concurrent.CancellationException

/**
 * Small on-device storage through Android's own SharedPreferences, so it works on every CloudStream version
 * (CloudStream's internal key store changed names between releases).
 */
internal object Persist {
    @Volatile var context: Context? = null

    fun read(key: String): String? = safely { context?.getSharedPreferences("brostream_ah", Context.MODE_PRIVATE)?.getString(key, null) }

    fun write(key: String, value: String) {
        safely { context?.getSharedPreferences("brostream_ah", Context.MODE_PRIVATE)?.edit()?.putString(key, value)?.apply() }
    }
}

/** Like [attempt] but also survives linkage errors from a CloudStream version missing an API. */
internal inline fun <T> safely(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

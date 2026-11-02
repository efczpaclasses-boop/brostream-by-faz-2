package com.epornergay

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class EpornerGayPlugin : Plugin() {
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences(OfficialCatalogue.PREFS, Context.MODE_PRIVATE)
        openSettings = { showOfficialRows(it, prefs) }
        registerMainAPI(EpornerGayProvider { prefs.getStringSet(OfficialCatalogue.PREFS_KEY, null) })
    }

    private fun showOfficialRows(context: Context, prefs: SharedPreferences) {
        val selectable = OfficialCatalogue.allRows().filter { !it.core }
        val saved = prefs.getStringSet(OfficialCatalogue.PREFS_KEY, null)
        val checked = BooleanArray(selectable.size) { index ->
            saved?.contains(selectable[index].id) ?: selectable[index].defaultOn
        }
        AlertDialog.Builder(context)
            .setTitle("Official rows. Core feeds stay on. Two to five Chaturbate tags also add one combined row.")
            .setMultiChoiceItems(selectable.map { it.label }.toTypedArray(), checked) { _, which, on ->
                checked[which] = on
            }
            .setPositiveButton("Save") { _, _ ->
                val chosen = selectable.mapIndexedNotNull { index, row -> row.id.takeIf { checked[index] } }.toSet()
                prefs.edit().putStringSet(OfficialCatalogue.PREFS_KEY, chosen).apply()
            }
            .setNeutralButton("Defaults") { _, _ ->
                prefs.edit().remove(OfficialCatalogue.PREFS_KEY).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

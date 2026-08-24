package com.mochits.app.settings

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mochits_settings", Context.MODE_PRIVATE)

    var exportLocation: String
        get() = prefs.getString("export_location", "Pictures/MochiTs") ?: "Pictures/MochiTs"
        set(value) = prefs.edit().putString("export_location", value).apply()

    var exportQuality: Int
        get() = prefs.getInt("export_quality", 100)
        set(value) = prefs.edit().putInt("export_quality", value).apply()
}

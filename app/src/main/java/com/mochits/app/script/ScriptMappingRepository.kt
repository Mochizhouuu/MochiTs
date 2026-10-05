package com.mochits.app.script

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pemetaan simbol script TL (F-script): awalan baris -> preset style.
 * Disimpan sebagai JSON di SharedPreferences mengikuti pola
 * StylePresetRepository.
 */
data class ScriptSymbolEntry(
    val symbol: String,
    /** Id preset; kosong = teks polos ikut style aktif. */
    val presetId: String = "",
    /**
     * true = sambung ke entri sebelumnya (mis. `//-` menempel ke balon
     * sebelumnya), bukan entri baru.
     */
    val append: Boolean = false
)

@Singleton
class ScriptMappingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _mappings = MutableStateFlow(loadMappings())
    val mappings: StateFlow<List<ScriptSymbolEntry>> = _mappings.asStateFlow()

    private val _separator = MutableStateFlow(loadSeparator())
    /** Baris pemisah halaman (trimmed, exact match). */
    val separator: StateFlow<String> = _separator.asStateFlow()

    fun getMappings(): List<ScriptSymbolEntry> = _mappings.value
    fun getSeparator(): String = _separator.value

    /** Muat ulang dari prefs (untuk pembaca yang memegang instance lain). */
    fun reload() {
        _mappings.value = loadMappings()
        _separator.value = loadSeparator()
    }

    fun setMappings(entries: List<ScriptSymbolEntry>) {
        val clean = entries
            .map { it.copy(symbol = it.symbol.trim()) }
            .filter { it.symbol.isNotEmpty() }
            .distinctBy { it.symbol }
        try {
            prefs.edit().putString(KEY_MAPPINGS, gson.toJson(clean)).apply()
        } catch (_: Exception) {}
        _mappings.value = clean
    }

    fun setSeparator(sep: String) {
        val clean = sep.trim()
        try {
            prefs.edit().putString(KEY_SEPARATOR, clean).apply()
        } catch (_: Exception) {}
        _separator.value = clean
    }

    private fun loadMappings(): List<ScriptSymbolEntry> {
        val json = prefs.getString(KEY_MAPPINGS, null)
        if (json != null) {
            try {
                val type = object : TypeToken<List<ScriptSymbolEntry>>() {}.type
                val list: List<ScriptSymbolEntry>? = gson.fromJson(json, type)
                if (list != null) {
                    return list.map { it.copy(symbol = it.symbol.trim()) }
                        .filter { it.symbol.isNotEmpty() }
                        .distinctBy { it.symbol }
                }
            } catch (_: Exception) {}
        }
        return defaultMappings()
    }

    private fun loadSeparator(): String {
        return prefs.getString(KEY_SEPARATOR, DEFAULT_SEPARATOR) ?: DEFAULT_SEPARATOR
    }

    companion object {
        private const val PREFS_NAME = "mochits_script_mapping"
        private const val KEY_MAPPINGS = "symbol_mappings_json"
        private const val KEY_SEPARATOR = "page_separator"
        /** Regex full-match: halaman "P1".."P99" ("===" literal juga cocok). */
        const val DEFAULT_SEPARATOR = "P\\d+"

        /** Default dari legenda TL nyata (preset dipasangkan user). */
        fun defaultMappings(): List<ScriptSymbolEntry> = listOf(
            ScriptSymbolEntry("[]"),
            ScriptSymbolEntry("-"),
            ScriptSymbolEntry("//-", append = true),
            ScriptSymbolEntry("//[]", append = true),
            ScriptSymbolEntry("#"),
            ScriptSymbolEntry("*"),
            ScriptSymbolEntry("**"),
            ScriptSymbolEntry("<>")
        )
    }
}

package com.mochits.app.style

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStylePreset
import com.mochits.app.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Menyimpan preset gaya teks (Font + Alignment + Shape).
 * Preset bawaan selalu ada; preset pengguna disimpan sebagai JSON
 * di SharedPreferences mengikuti pola ExportSettingsRepository.
 */
@Singleton
class StylePresetRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _presets = MutableStateFlow(loadAll())
    val presets: StateFlow<List<TextStylePreset>> = _presets.asStateFlow()

    fun savePreset(preset: TextStylePreset): TextStylePreset {
        val clean = preset.copy(
            name = preset.name.trim().take(40).ifEmpty { "Tanpa Nama" },
            isBuiltIn = false
        )
        val customs = loadCustoms().toMutableList()
        val index = customs.indexOfFirst { it.id == clean.id }
        if (index >= 0) {
            customs[index] = clean
        } else {
            customs.add(clean)
        }
        persistCustoms(customs)
        _presets.value = loadAll()
        return clean
    }

    /** Preset bawaan tidak bisa dihapus; mengembalikan false jika ditolak. */
    fun deletePreset(id: String): Boolean {
        val customs = loadCustoms().toMutableList()
        val removed = customs.removeAll { it.id == id }
        if (!removed) return false
        persistCustoms(customs)
        _presets.value = loadAll()
        return true
    }

    fun getPreset(id: String): TextStylePreset? = _presets.value.find { it.id == id }

    private fun loadAll(): List<TextStylePreset> = builtInPresets() + loadCustoms()

    private fun loadCustoms(): List<TextStylePreset> {
        val json = prefs.getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TextStylePreset>>() {}.type
            (gson.fromJson<List<TextStylePreset>>(json, type) ?: emptyList())
                .filter { it.name.isNotBlank() }
        } catch (t: Throwable) {
            Logger.e("Error loading style presets: ${t.message}", t)
            emptyList()
        }
    }

    private fun persistCustoms(customs: List<TextStylePreset>) {
        try {
            prefs.edit().putString(KEY_CUSTOM_PRESETS, gson.toJson(customs)).apply()
        } catch (t: Throwable) {
            Logger.e("Error saving style presets: ${t.message}", t)
        }
    }

    private fun builtInPresets(): List<TextStylePreset> = listOf(
        TextStylePreset(
            id = "builtin-judul",
            name = "Judul",
            fontName = "Sans",
            fontStyle = "Bold",
            alignment = TextAlignment.CENTER,
            shape = TextContainerShape.BOX,
            isBuiltIn = true
        ),
        TextStylePreset(
            id = "builtin-dialog",
            name = "Dialog",
            fontName = "Default",
            fontStyle = "Regular",
            alignment = TextAlignment.CENTER,
            shape = TextContainerShape.OVAL,
            isBuiltIn = true
        ),
        TextStylePreset(
            id = "builtin-narasi",
            name = "Narasi",
            fontName = "Serif",
            fontStyle = "Italic",
            alignment = TextAlignment.LEFT,
            shape = TextContainerShape.BOX,
            isBuiltIn = true
        ),
        TextStylePreset(
            id = "builtin-teriakan",
            name = "Teriakan",
            fontName = "Monospace",
            fontStyle = "Bold",
            alignment = TextAlignment.CENTER,
            shape = TextContainerShape.OVAL,
            isBuiltIn = true
        )
    )

    companion object {
        private const val PREFS_NAME = "mochits_style_presets"
        private const val KEY_CUSTOM_PRESETS = "custom_presets_json"
    }
}

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

    private val _hiddenBuiltInIds = MutableStateFlow(loadHiddenBuiltIns())
    /** ID preset bawaan yang disembunyikan pengguna. */
    val hiddenBuiltInIds: StateFlow<Set<String>> = _hiddenBuiltInIds.asStateFlow()

    private val _pinnedPresetIds = MutableStateFlow(loadPinnedIds())
    /** ID preset yang disematkan; tampil paling atas sesuai urutan ini. */
    val pinnedPresetIds: StateFlow<List<String>> = _pinnedPresetIds.asStateFlow()

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

    /**
     * Menghapus preset kustom, atau menyembunyikan preset bawaan yang tidak terpakai.
     * Preset bawaan yang disembunyikan bisa dikembalikan via [restoreBuiltInPreset].
     * @return false bila id tidak dikenal.
     */
    fun deletePreset(id: String): Boolean {
        val customs = loadCustoms().toMutableList()
        if (customs.removeAll { it.id == id }) {
            persistCustoms(customs)
            val order = loadPinnedIds().toMutableList()
            if (order.remove(id)) {
                persistPinnedIds(order)
                _pinnedPresetIds.value = order
            }
            _presets.value = loadAll()
            return true
        }
        if (builtInPresets().any { it.id == id }) {
            val hidden = loadHiddenBuiltIns().toMutableSet()
            if (!hidden.add(id)) return false
            persistHiddenBuiltIns(hidden)
            _hiddenBuiltInIds.value = hidden
            _presets.value = loadAll()
            return true
        }
        return false
    }

    /** Mengembalikan preset bawaan yang disembunyikan agar muncul lagi. */
    fun restoreBuiltInPreset(id: String): Boolean {
        val hidden = loadHiddenBuiltIns().toMutableSet()
        if (!hidden.remove(id)) return false
        persistHiddenBuiltIns(hidden)
        _hiddenBuiltInIds.value = hidden
        _presets.value = loadAll()
        return true
    }

    /** Mengembalikan semua preset bawaan yang disembunyikan. */
    fun restoreAllBuiltIns() {
        persistHiddenBuiltIns(emptySet())
        _hiddenBuiltInIds.value = emptySet()
        _presets.value = loadAll()
    }

    /**
     * Menyematkan preset agar selalu tampil paling atas (false = lepas).
     * Yang baru disematkan menempati urutan teratas.
     * @return false bila id tidak dikenal.
     */
    fun setPresetPinned(id: String, pinned: Boolean): Boolean {
        val known = (builtInPresets() + loadCustoms()).any { it.id == id }
        if (!known) return false
        val order = loadPinnedIds().toMutableList()
        order.remove(id)
        if (pinned) order.add(0, id)
        persistPinnedIds(order)
        _pinnedPresetIds.value = order
        _presets.value = loadAll()
        return true
    }

    fun getPreset(id: String): TextStylePreset? = _presets.value.find { it.id == id }

    /** JSON berisi preset buatan pengguna saja (bawaan tidak perlu dibagikan). */
    fun exportCustomsJson(): String = gson.toJson(loadCustoms())

    /**
     * Impor preset dari JSON hasil [exportCustomsJson].
     * @return jumlah preset yang berhasil ditambahkan.
     * @throws IllegalArgumentException jika JSON bukan daftar preset yang valid.
     */
    fun importPresetsJson(json: String): Int {
        val type = object : TypeToken<List<TextStylePreset>>() {}.type
        val parsed: List<TextStylePreset> = try {
            gson.fromJson<List<TextStylePreset>>(json, type)
                ?: throw IllegalArgumentException("File JSON kosong.")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (t: Throwable) {
            throw IllegalArgumentException("File JSON tidak valid: ${t.message}")
        }
        if (parsed.isEmpty()) throw IllegalArgumentException("Tidak ada preset di file ini.")
        val customs = loadCustoms().toMutableList()
        val takenIds = (builtInPresets() + customs).map { it.id }.toMutableSet()
        var added = 0
        for (item in parsed) {
            if (item.name.isBlank()) continue
            val clean = item.copy(
                id = if (item.id.isBlank() || !takenIds.add(item.id)) {
                    java.util.UUID.randomUUID().toString().also { takenIds.add(it) }
                } else {
                    item.id
                },
                name = item.name.trim().take(40),
                isBuiltIn = false
            )
            customs.add(clean)
            added++
        }
        if (added == 0) throw IllegalArgumentException("Tidak ada preset valid di file ini.")
        persistCustoms(customs)
        _presets.value = loadAll()
        return added
    }

    private fun loadAll(): List<TextStylePreset> {
        val hidden = loadHiddenBuiltIns()
        val visible = builtInPresets().filter { it.id !in hidden } + loadCustoms()
        val pinnedOrder = loadPinnedIds().filter { id -> visible.any { it.id == id } }
        val pinned = pinnedOrder.mapNotNull { id -> visible.find { it.id == id } }
        val pinnedIds = pinned.map { it.id }.toSet()
        return pinned + visible.filter { it.id !in pinnedIds }
    }

    private fun loadHiddenBuiltIns(): Set<String> {
        val json = prefs.getString(KEY_HIDDEN_BUILTINS, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson<Set<String>>(json, type) ?: emptySet()
        } catch (t: Throwable) {
            Logger.e("Error loading hidden presets: ${t.message}", t)
            emptySet()
        }
    }

    private fun persistHiddenBuiltIns(ids: Set<String>) {
        try {
            prefs.edit().putString(KEY_HIDDEN_BUILTINS, gson.toJson(ids.toList())).apply()
        } catch (t: Throwable) {
            Logger.e("Error saving hidden presets: ${t.message}", t)
        }
    }

    private fun loadPinnedIds(): List<String> {
        val json = prefs.getString(KEY_PINNED_IDS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type) ?: emptyList()
        } catch (t: Throwable) {
            Logger.e("Error loading pinned presets: ${t.message}", t)
            emptyList()
        }
    }

    private fun persistPinnedIds(ids: List<String>) {
        try {
            prefs.edit().putString(KEY_PINNED_IDS, gson.toJson(ids)).apply()
        } catch (t: Throwable) {
            Logger.e("Error saving pinned presets: ${t.message}", t)
        }
    }

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
        private const val KEY_HIDDEN_BUILTINS = "hidden_builtin_ids_json"
        private const val KEY_PINNED_IDS = "pinned_preset_ids_json"
    }
}

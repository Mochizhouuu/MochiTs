package com.mochits.text

import android.content.Context
import com.mochits.common.OperationResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TextStylePreset(
    val id: String,
    val name: String,
    val style: TextStyleConfig
)

class TextPresetManager(private val context: Context) {
    private val presetsFile = File(context.filesDir, "text_presets.json")

    val defaultPresets: List<TextStylePreset> = listOf(
        TextStylePreset(
            id = "preset_dialog",
            name = "Balon Dialog",
            style = TextStyleConfig(
                colorArgb = 0xFF000000.toInt(),
                strokeEnabled = false,
                isBold = false
            )
        ),
        TextStylePreset(
            id = "preset_box",
            name = "Kotak Narasi",
            style = TextStyleConfig(
                colorArgb = 0xFF111111.toInt(),
                strokeEnabled = true,
                strokeColorArgb = 0xFFFFFFFF.toInt(),
                strokeWidthPx = 3f,
                isBold = true
            )
        ),
        TextStylePreset(
            id = "preset_whisper",
            name = "Bisik-bisik",
            style = TextStyleConfig(
                colorArgb = 0xFF666666.toInt(),
                isItalic = true,
                strokeEnabled = false
            )
        ),
        TextStylePreset(
            id = "preset_scream",
            name = "Teriakan / SFX",
            style = TextStyleConfig(
                colorArgb = 0xFFD32F2F.toInt(),
                isBold = true,
                strokeEnabled = true,
                strokeColorArgb = 0xFFFFFFFF.toInt(),
                strokeWidthPx = 6f,
                glowEnabled = true,
                glowColorArgb = 0xFFFFEB3B.toInt(),
                glowRadius = 14f
            )
        )
    )

    fun getPresets(): List<TextStylePreset> {
        val userPresets = loadUserPresets()
        return defaultPresets + userPresets
    }

    /**
     * Simpan preset baru. Mengembalikan [OperationResult] — tulis file bisa
     * gagal (storage penuh) dan caller wajib tahu, jangan diam-diam dianggap
     * sukses seperti implementasi lama.
     */
    fun savePreset(name: String, style: TextStyleConfig): OperationResult<TextStylePreset> {
        val current = loadUserPresets().toMutableList()
        val newPreset = TextStylePreset(
            id = "preset_user_${System.currentTimeMillis()}",
            name = name,
            style = style
        )
        current.add(newPreset)
        return try {
            saveUserPresets(current)
            OperationResult.Success(newPreset)
        } catch (e: Exception) {
            OperationResult.Failure(e, "Gagal menyimpan preset: ${e.message}")
        }
    }

    fun deletePreset(id: String): Boolean {
        if (id.startsWith("preset_user_")) {
            val current = loadUserPresets().toMutableList()
            val removed = current.removeAll { it.id == id }
            if (removed) {
                saveUserPresets(current)
                return true
            }
        }
        return false
    }

    private fun loadUserPresets(): List<TextStylePreset> {
        if (!presetsFile.exists()) return emptyList()
        // Parse PER-ELEMEN: satu entri korup hanya dilewati, tidak boleh
        // menghapus seluruh preset user (implementasi lama mengembalikan
        // emptyList lalu save berikutnya menimpa file - data loss permanen).
        val list = mutableListOf<TextStylePreset>()
        runCatching {
            val jsonArray = JSONArray(presetsFile.readText())
            for (i in 0 until jsonArray.length()) {
                runCatching { deserializePreset(jsonArray.getJSONObject(i)) }
                    .onSuccess { list.add(it) }
            }
        }
        return list
    }

    /** Tulis via file .tmp + rename atomik; lempar exception bila gagal agar [savePreset] melaporkan kegagalan. */
    private fun saveUserPresets(presets: List<TextStylePreset>) {
        val jsonArray = JSONArray()
        presets.forEach { preset ->
            jsonArray.put(serializePreset(preset))
        }
        val tmp = File(presetsFile.parentFile, presetsFile.name + ".tmp")
        tmp.writeText(jsonArray.toString())
        if (!tmp.renameTo(presetsFile)) {
            tmp.delete()
            throw java.io.IOException("Gagal menulis file preset")
        }
    }

    private fun serializePreset(preset: TextStylePreset): JSONObject {
        val obj = JSONObject()
        obj.put("id", preset.id)
        obj.put("name", preset.name)
        obj.put("style", TextStyleJson.toJson(preset.style))
        return obj
    }

    private fun deserializePreset(obj: JSONObject): TextStylePreset {
        val id = obj.getString("id")
        val name = obj.getString("name")
        val sObj = obj.getJSONObject("style")
        return TextStylePreset(id = id, name = name, style = TextStyleJson.fromJson(sObj))
    }
}

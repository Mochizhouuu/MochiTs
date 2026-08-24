package com.mochits.text

import android.content.Context
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

    fun savePreset(name: String, style: TextStyleConfig): TextStylePreset {
        val current = loadUserPresets().toMutableList()
        val newPreset = TextStylePreset(
            id = "preset_user_${System.currentTimeMillis()}",
            name = name,
            style = style
        )
        current.add(newPreset)
        saveUserPresets(current)
        return newPreset
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
        return try {
            val jsonStr = presetsFile.readText()
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<TextStylePreset>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(deserializePreset(obj))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveUserPresets(presets: List<TextStylePreset>) {
        try {
            val jsonArray = JSONArray()
            presets.forEach { preset ->
                jsonArray.put(serializePreset(preset))
            }
            presetsFile.writeText(jsonArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun serializePreset(preset: TextStylePreset): JSONObject {
        val obj = JSONObject()
        obj.put("id", preset.id)
        obj.put("name", preset.name)

        val s = preset.style
        val styleObj = JSONObject()
        styleObj.put("colorArgb", s.colorArgb)
        styleObj.put("isBold", s.isBold)
        styleObj.put("isItalic", s.isItalic)
        styleObj.put("isUnderline", s.isUnderline)
        styleObj.put("isStrikethrough", s.isStrikethrough)
        styleObj.put("alignment", s.alignment.name)
        styleObj.put("fontPath", s.fontPath ?: "")
        styleObj.put("strokeEnabled", s.strokeEnabled)
        styleObj.put("strokeWidthPx", s.strokeWidthPx.toDouble())
        styleObj.put("strokeColorArgb", s.strokeColorArgb)
        styleObj.put("shadowEnabled", s.shadowEnabled)
        styleObj.put("shadowDx", s.shadowDx.toDouble())
        styleObj.put("shadowDy", s.shadowDy.toDouble())
        styleObj.put("shadowRadius", s.shadowRadius.toDouble())
        styleObj.put("shadowColorArgb", s.shadowColorArgb)
        styleObj.put("glowEnabled", s.glowEnabled)
        styleObj.put("glowRadius", s.glowRadius.toDouble())
        styleObj.put("glowColorArgb", s.glowColorArgb)
        styleObj.put("motionBlurEnabled", s.motionBlurEnabled)
        styleObj.put("motionBlurAngle", s.motionBlurAngle.toDouble())
        styleObj.put("motionBlurDistance", s.motionBlurDistance.toDouble())

        obj.put("style", styleObj)
        return obj
    }

    private fun deserializePreset(obj: JSONObject): TextStylePreset {
        val id = obj.getString("id")
        val name = obj.getString("name")
        val sObj = obj.getJSONObject("style")

        val fontPath = sObj.optString("fontPath", "").ifEmpty { null }
        val alignStr = sObj.optString("alignment", TextAlignment.LEFT.name)
        val align = try { TextAlignment.valueOf(alignStr) } catch (e: Exception) { TextAlignment.LEFT }

        val style = TextStyleConfig(
            colorArgb = sObj.optInt("colorArgb", 0xFF000000.toInt()),
            isBold = sObj.optBoolean("isBold", false),
            isItalic = sObj.optBoolean("isItalic", false),
            isUnderline = sObj.optBoolean("isUnderline", false),
            isStrikethrough = sObj.optBoolean("isStrikethrough", false),
            alignment = align,
            fontPath = fontPath,
            strokeEnabled = sObj.optBoolean("strokeEnabled", false),
            strokeWidthPx = sObj.optDouble("strokeWidthPx", 4.0).toFloat(),
            strokeColorArgb = sObj.optInt("strokeColorArgb", 0xFFFFFFFF.toInt()),
            shadowEnabled = sObj.optBoolean("shadowEnabled", false),
            shadowDx = sObj.optDouble("shadowDx", 4.0).toFloat(),
            shadowDy = sObj.optDouble("shadowDy", 4.0).toFloat(),
            shadowRadius = sObj.optDouble("shadowRadius", 6.0).toFloat(),
            shadowColorArgb = sObj.optInt("shadowColorArgb", 0x80000000.toInt()),
            glowEnabled = sObj.optBoolean("glowEnabled", false),
            glowRadius = sObj.optDouble("glowRadius", 12.0).toFloat(),
            glowColorArgb = sObj.optInt("glowColorArgb", 0xFFFFEA00.toInt()),
            motionBlurEnabled = sObj.optBoolean("motionBlurEnabled", false),
            motionBlurAngle = sObj.optDouble("motionBlurAngle", 0.0).toFloat(),
            motionBlurDistance = sObj.optDouble("motionBlurDistance", 8.0).toFloat()
        )

        return TextStylePreset(id = id, name = name, style = style)
    }
}

package com.mochits.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec JSON tunggal untuk [TextStyleConfig]. Dipakai bersama oleh
 * layers.json project ([com.mochits.app.editor.LayerJsonSerializer]) dan
 * preset style ([TextPresetManager]) agar field yang disimpan & dibaca
 * selalu konsisten — sebelumnya masing-masing menulis serialisasinya
 * sendiri sehingga gradient/curve hilang dan default bisa berbeda.
 */
object TextStyleJson {

    fun toJson(s: TextStyleConfig): JSONObject {
        val sObj = JSONObject()
        sObj.put("colorArgb", s.colorArgb)
        sObj.put("fontPath", s.fontPath ?: JSONObject.NULL)
        sObj.put("isBold", s.isBold)
        sObj.put("isItalic", s.isItalic)
        sObj.put("isUnderline", s.isUnderline)
        sObj.put("isStrikethrough", s.isStrikethrough)
        sObj.put("alignment", s.alignment.name)
        sObj.put("strokeEnabled", s.strokeEnabled)
        sObj.put("strokeColorArgb", s.strokeColorArgb)
        sObj.put("strokeWidthPx", s.strokeWidthPx.toDouble())
        sObj.put("glowEnabled", s.glowEnabled)
        sObj.put("glowColorArgb", s.glowColorArgb)
        sObj.put("glowRadius", s.glowRadius.toDouble())
        sObj.put("shadowEnabled", s.shadowEnabled)
        sObj.put("shadowColorArgb", s.shadowColorArgb)
        sObj.put("shadowRadius", s.shadowRadius.toDouble())
        sObj.put("shadowDx", s.shadowDx.toDouble())
        sObj.put("shadowDy", s.shadowDy.toDouble())
        sObj.put("motionBlurEnabled", s.motionBlurEnabled)
        sObj.put("motionBlurAngle", s.motionBlurAngle.toDouble())
        sObj.put("motionBlurDistance", s.motionBlurDistance.toDouble())
        sObj.put("gradientEnabled", s.gradientEnabled)
        sObj.put("gradientColors", JSONArray(s.gradientColors))
        sObj.put("curveEnabled", s.curveEnabled)
        sObj.put("curveRadius", s.curveRadius.toDouble())
        return sObj
    }

    fun fromJson(sObj: JSONObject): TextStyleConfig {
        val fontPath: String? =
            if (sObj.isNull("fontPath")) null
            else sObj.optString("fontPath").ifEmpty { null }
        val alignment = runCatching { TextAlignment.valueOf(sObj.optString("alignment", "LEFT")) }
            .getOrDefault(TextAlignment.LEFT)

        val gradientColors = mutableListOf<Int>()
        val gradArr = sObj.optJSONArray("gradientColors")
        if (gradArr != null) {
            for (i in 0 until gradArr.length()) {
                gradientColors.add(gradArr.optInt(i))
            }
        }

        // Nilai fallback SAMA dengan default TextStyleConfig agar data lama
        // tanpa field eksplisit tetap tampil seperti aslinya.
        return TextStyleConfig(
            colorArgb = sObj.optInt("colorArgb", 0xFF000000.toInt()),
            fontPath = fontPath,
            isBold = sObj.optBoolean("isBold", false),
            isItalic = sObj.optBoolean("isItalic", false),
            isUnderline = sObj.optBoolean("isUnderline", false),
            isStrikethrough = sObj.optBoolean("isStrikethrough", false),
            alignment = alignment,
            strokeEnabled = sObj.optBoolean("strokeEnabled", false),
            strokeColorArgb = sObj.optInt("strokeColorArgb", 0xFFFFFFFF.toInt()),
            strokeWidthPx = sObj.optDouble("strokeWidthPx", 4.0).toFloat(),
            glowEnabled = sObj.optBoolean("glowEnabled", false),
            glowColorArgb = sObj.optInt("glowColorArgb", 0xFFFFEA00.toInt()),
            glowRadius = sObj.optDouble("glowRadius", 12.0).toFloat(),
            shadowEnabled = sObj.optBoolean("shadowEnabled", false),
            shadowColorArgb = sObj.optInt("shadowColorArgb", 0x80000000.toInt()),
            shadowRadius = sObj.optDouble("shadowRadius", 6.0).toFloat(),
            shadowDx = sObj.optDouble("shadowDx", 4.0).toFloat(),
            shadowDy = sObj.optDouble("shadowDy", 4.0).toFloat(),
            motionBlurEnabled = sObj.optBoolean("motionBlurEnabled", false),
            motionBlurAngle = sObj.optDouble("motionBlurAngle", 0.0).toFloat(),
            motionBlurDistance = sObj.optDouble("motionBlurDistance", 8.0).toFloat(),
            gradientEnabled = sObj.optBoolean("gradientEnabled", false),
            gradientColors = if (gradientColors.size >= 2) gradientColors
            else listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            curveEnabled = sObj.optBoolean("curveEnabled", false),
            curveRadius = sObj.optDouble("curveRadius", 150.0).toFloat()
        )
    }
}

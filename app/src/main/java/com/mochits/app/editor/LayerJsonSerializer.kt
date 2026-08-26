package com.mochits.app.editor

import com.mochits.canvas.CanvasTextLayer
import com.mochits.text.TextAlignment
import com.mochits.text.TextStyleConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializer TUNGGAL untuk layers.json project.
 *
 * Sebelumnya serialisasi (save) dan deserialisasi (load) ditulis manual
 * di dua tempat berbeda sehingga bisa desinkron: field gradient tidak
 * ikut tersimpan, id layer di-regenerate, dan nilai default fallback
 * berbeda dari default [TextStyleConfig]. Semua kini lewat sini agar
 * save & load selalu konsisten.
 */
object LayerJsonSerializer {

    fun serialize(layers: List<CanvasTextLayer>): String {
        val jsonArray = JSONArray()
        layers.forEach { layer ->
            val obj = JSONObject()
            obj.put("id", layer.id)
            obj.put("text", layer.text)
            obj.put("xInImagePx", layer.xInImagePx.toDouble())
            obj.put("yInImagePx", layer.yInImagePx.toDouble())
            obj.put("fontSizeSp", layer.fontSizeSp.toDouble())
            obj.put("style", serializeStyle(layer.style))
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    /** @return daftar layer; entri yang korup dilewati, bukan menggagalkan semuanya. */
    fun deserialize(json: String): List<CanvasTextLayer> {
        val result = mutableListOf<CanvasTextLayer>()
        runCatching {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                runCatching {
                    val obj = jsonArray.getJSONObject(i)
                    result.add(
                        CanvasTextLayer(
                            // Pertahankan id tersimpan; fallback unik bila korup/hilang
                            id = obj.optString("id").ifBlank { "text-loaded-$i" },
                            text = obj.getString("text"),
                            xInImagePx = obj.getDouble("xInImagePx").toFloat(),
                            yInImagePx = obj.getDouble("yInImagePx").toFloat(),
                            fontSizeSp = obj.optDouble("fontSizeSp", 24.0).toFloat(),
                            style = if (obj.has("style")) deserializeStyle(obj.getJSONObject("style"))
                            else TextStyleConfig()
                        )
                    )
                }
            }
        }
        return result
    }

    private fun serializeStyle(s: com.mochits.text.TextStyleConfig): JSONObject =
        com.mochits.text.TextStyleJson.toJson(s)

    private fun deserializeStyle(sObj: JSONObject): com.mochits.text.TextStyleConfig =
        com.mochits.text.TextStyleJson.fromJson(sObj)
}

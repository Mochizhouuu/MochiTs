package com.mochits.app.font

data class FontItem(
    val name: String,
    val fontNameKey: String,
    val isCustom: Boolean = false,
    val filePath: String? = null,
    val assetPath: String? = null
)

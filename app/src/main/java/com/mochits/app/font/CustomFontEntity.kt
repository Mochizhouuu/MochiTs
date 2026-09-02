package com.mochits.app.font

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_fonts")
data class CustomFontEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val filePath: String,
    val addedAt: Long = System.currentTimeMillis()
)

package com.mochits.app.project

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mochits.app.font.CustomFontDao
import com.mochits.app.font.CustomFontEntity

@Database(
    entities = [ProjectEntity::class, CustomFontEntity::class],
    version = 2,
    // exportSchema=false PERMANEN: exportSchema=true membuat task KSP
    // debug+release balapan menulis schemas/2.json yang sama ("Empty schema
    // file", flaky). Proteksi data tetap via MIGRATION_1_2 + fallback
    // downgrade-only yang tidak butuh export.
    exportSchema = false
)
abstract class MochiTsDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun customFontDao(): CustomFontDao
}

package com.mochits.app.project

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mochits.app.font.CustomFontDao
import com.mochits.app.font.CustomFontEntity

@Database(
    entities = [ProjectEntity::class, CustomFontEntity::class],
    version = 2,
    // exportSchema dimatikan (B7-revisi): exportSchema=true membuat KSP
    // Room 2.6.1 gagal ("Empty schema file") di CI. Proteksi data tetap
    // dipegang MIGRATION_1_2 + fallback downgrade-only di AppModule.
    exportSchema = false
)
abstract class MochiTsDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun customFontDao(): CustomFontDao
}

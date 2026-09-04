package com.mochits.app.project

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mochits.app.font.CustomFontDao
import com.mochits.app.font.CustomFontEntity

@Database(
    entities = [ProjectEntity::class, CustomFontEntity::class],
    version = 2,
    exportSchema = false
)
abstract class MochiTsDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun customFontDao(): CustomFontDao
}

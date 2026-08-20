package com.mochits.project

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class MochiTsDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        const val DATABASE_NAME = "mochits.db"
    }
}

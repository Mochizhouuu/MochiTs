package com.mochits.app.project

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ProjectEntity::class], version = 1, exportSchema = false)
abstract class MochiTsDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
}

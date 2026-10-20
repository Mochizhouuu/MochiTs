package com.mochits.app.project

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Folder virtual untuk mengelompokkan proyek di Home (bukan folder file). */
@Entity(tableName = "project_folders")
data class ProjectFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

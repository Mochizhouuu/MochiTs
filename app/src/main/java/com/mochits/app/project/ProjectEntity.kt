package com.mochits.app.project

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val thumbnailPath: String? = null,
    val layersJson: String = "[]"
)

package com.mochits.project

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity Room untuk metadata project. Aset gambar (base image, mask)
 * disimpan di filesystem lokal (app-specific storage) — hanya path-nya
 * yang disimpan di sini.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseImagePath: String,
    val maskPath: String?,
    val thumbnailPath: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

fun ProjectEntity.toProjectFile() = ProjectFile(
    id = id,
    name = name,
    baseImagePath = baseImagePath,
    maskPath = maskPath,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs
)

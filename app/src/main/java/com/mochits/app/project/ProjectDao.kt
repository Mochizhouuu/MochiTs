package com.mochits.app.project

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("UPDATE projects SET folderId = :folderId WHERE id = :id")
    suspend fun setProjectFolder(id: String, folderId: String?)

    @Query("UPDATE projects SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT * FROM project_folders ORDER BY name COLLATE NOCASE ASC")
    fun getAllFolders(): Flow<List<ProjectFolderEntity>>

    @Query("SELECT * FROM project_folders WHERE id = :id")
    suspend fun getFolderById(id: String): ProjectFolderEntity?

    @Query("SELECT * FROM project_folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findFolderByName(name: String): ProjectFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: ProjectFolderEntity)

    @Update
    suspend fun updateFolder(folder: ProjectFolderEntity)

    @Delete
    suspend fun deleteFolder(folder: ProjectFolderEntity)
}

package com.mochits.app.editor

import com.mochits.inpaint.ModelManager
import com.mochits.project.ProjectRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface EditorRepositoryEntryPoint {
    fun projectRepository(): ProjectRepository
    fun modelManager(): ModelManager
}

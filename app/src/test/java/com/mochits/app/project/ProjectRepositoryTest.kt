package com.mochits.app.project

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectRepositoryTest {

    private lateinit var db: MochiTsDatabase
    private lateinit var dao: ProjectDao
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MochiTsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.projectDao()
        repository = ProjectRepository(context, dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createProject_handles_custom_dimensions_and_creates_entity() = runBlocking {
        val project = repository.createProject(
            title = "Test Large Project",
            width = 1080,
            height = 1920,
            imageUri = null,
            isTransparent = false,
            backgroundColor = Color.WHITE
        )

        assertNotNull(project)
        assertNotNull(project.id)
        assertEquals("Test Large Project", project.title)
        assertEquals(1080, project.width)
        assertEquals(1920, project.height)
        assertNotNull(project.thumbnailPath)
        assertTrue(File(project.thumbnailPath!!).exists())

        val retrieved = repository.getProject(project.id)
        assertNotNull(retrieved)
        assertEquals(project.id, retrieved?.id)
    }

    @Test
    fun createFolder_membuatNamaUnikCaseInsensitive() = runBlocking {
        val a = repository.createFolder("Bab Baru")
        val b = repository.createFolder("bab baru")
        assertEquals("Bab Baru", a.name)
        assertEquals("bab baru (2)", b.name)
        assertEquals(2, repository.getAllFolders().first().size)
    }

    @Test
    fun moveProject_danDeleteFolder_tidakMenghapusProyek() = runBlocking {
        val folder = repository.createFolder("Kelompok")
        val project = repository.createProject(
            title = "Di Folder",
            width = 100,
            height = 100,
            folderId = folder.id
        )
        assertEquals(folder.id, repository.getProject(project.id)?.folderId)

        val other = repository.createProject(title = "Lain", width = 100, height = 100)
        repository.moveProjectToFolder(other.id, folder.id)
        assertEquals(folder.id, repository.getProject(other.id)?.folderId)

        assertTrue(repository.deleteFolder(folder.id))
        assertNull(repository.getProject(project.id)?.folderId)
        assertNull(repository.getProject(other.id)?.folderId)
        assertNotNull(repository.getProject(project.id))
        assertNotNull(repository.getProject(other.id))
        assertTrue(repository.getAllFolders().first().isEmpty())
    }

    @Test
    fun renameFolder_mengubahNama() = runBlocking {
        val folder = repository.createFolder("Lama")
        assertTrue(repository.renameFolder(folder.id, "Baru"))
        assertEquals("Baru", repository.getAllFolders().first().single().name)
        assertTrue(!repository.renameFolder(folder.id, "   "))
    }

    @Test
    fun createProject_transparent_flag_persisted() = runBlocking {
        val project = repository.createProject(
            title = "Test Transparent Project",
            width = 1080,
            height = 1920,
            imageUri = null,
            isTransparent = true
        )

        assertNotNull(project)
        assertTrue(project.isTransparent)

        val retrieved = repository.getProject(project.id)
        assertNotNull(retrieved)
        assertTrue(retrieved!!.isTransparent)
    }
}

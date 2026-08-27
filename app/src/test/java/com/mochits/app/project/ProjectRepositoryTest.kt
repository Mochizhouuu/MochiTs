package com.mochits.app.project

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            height = 19200,
            imageUri = null,
            isTransparent = false,
            backgroundColor = Color.WHITE
        )

        assertNotNull(project)
        assertNotNull(project.id)
        assertEquals("Test Large Project", project.title)
        assertEquals(1080, project.width)
        assertEquals(19200, project.height)
        assertNotNull(project.thumbnailPath)
        assertTrue(File(project.thumbnailPath!!).exists())

        val retrieved = repository.getProject(project.id)
        assertNotNull(retrieved)
        assertEquals(project.id, retrieved?.id)
    }
}

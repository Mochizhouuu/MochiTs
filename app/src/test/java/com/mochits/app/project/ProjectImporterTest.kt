package com.mochits.app.project

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectImporterTest {

    private lateinit var db: MochiTsDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var importer: ProjectImporter
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cacheDir = context.cacheDir
        db = Room.inMemoryDatabaseBuilder(context, MochiTsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProjectRepository(context, db.projectDao())
        importer = ProjectImporter(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importZip_membuatFolderRootDanSubfolder() = runBlocking {
        val zip = writeZip(
            "Komik Bab 1.zip",
            listOf(
                "10.png" to pngBytes(Color.RED),
                "2.png" to pngBytes(Color.BLUE),
                "Bab 2/1.png" to pngBytes(Color.GREEN),
                "__MACOSX/._2.png" to pngBytes(Color.BLACK),
                "catatan.txt" to "abaikan".toByteArray()
            )
        )

        val result = importer.importZip(Uri.fromFile(zip), "Komik Bab 1.zip")

        assertEquals(3, result.projects)
        assertEquals(0, result.bundles)
        assertEquals(0, result.failed)
        assertEquals(3, result.total)

        val folders = repository.getAllFolders().first()
        assertEquals(2, folders.size)
        val root = folders.first { it.name == "Komik Bab 1" }
        val sub = folders.first { it.name == "Bab 2" }

        val projects = repository.getAllProjects().first()
        assertEquals(3, projects.size)
        assertEquals(2, projects.count { it.folderId == root.id })
        assertEquals(1, projects.count { it.folderId == sub.id })
        assertTrue(projects.all { it.thumbnailPath != null })
    }

    @Test
    fun importImages_banyakGambar_membuatFolderOtomatis() = runBlocking {
        val a = File(cacheDir, "a.png").apply { writeBytes(pngBytes(Color.RED)) }
        val b = File(cacheDir, "b.png").apply { writeBytes(pngBytes(Color.BLUE)) }

        val result = importer.importImages(
            uris = listOf(Uri.fromFile(a), Uri.fromFile(b)),
            autoFolderName = "Impor Tes"
        )

        assertEquals(2, result.projectIds.size)
        assertEquals(0, result.failed)
        assertNotNull(result.folderId)
        assertEquals("Impor Tes", result.folderName)
        val folder = repository.getAllFolders().first().single()
        assertEquals(result.folderId, folder.id)
        assertEquals(2, repository.getAllProjects().first().count { it.folderId == folder.id })
    }

    @Test
    fun importImages_satuGambar_tanpaFolder() = runBlocking {
        val a = File(cacheDir, "single.png").apply { writeBytes(pngBytes(Color.RED)) }
        val result = importer.importImages(listOf(Uri.fromFile(a)))
        assertEquals(1, result.projectIds.size)
        assertNull(result.folderId)
        assertTrue(repository.getAllFolders().first().isEmpty())
        assertNull(repository.getAllProjects().first().single().folderId)
    }

    private fun pngBytes(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun writeZip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(cacheDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}

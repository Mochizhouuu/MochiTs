package com.mochits.project

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mochits.common.OperationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectRepositoryZipSlipTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var projectDao: ProjectDao
    private lateinit var repository: ProjectRepository

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        contentResolver = mock(ContentResolver::class.java)
        projectDao = mock(ProjectDao::class.java)

        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")

        `when`(context.filesDir).thenReturn(filesDir)
        `when`(context.cacheDir).thenReturn(cacheDir)
        `when`(context.contentResolver).thenReturn(contentResolver)

        repository = ProjectRepository(context, projectDao)
    }

    @Test
    fun testImportProjects_ZipSlipBlocked() = runBlocking {
        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(ZipEntry("../../evil.txt"))
            zos.write("evil contents".toByteArray())
            zos.closeEntry()
        }

        val maliciousZipBytes = zipBaos.toByteArray()
        val dummyUri = mock(Uri::class.java)
        `when`(contentResolver.openInputStream(dummyUri)).thenReturn(ByteArrayInputStream(maliciousZipBytes))

        val result = repository.importProjectsFromUri(dummyUri)
        assertTrue(result is OperationResult.Failure)
        val failure = result as OperationResult.Failure
        assertTrue(failure.error is SecurityException)
        assertTrue(failure.error.message?.contains("Zip Slip") == true)
    }
}

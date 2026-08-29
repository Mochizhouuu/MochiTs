package com.mochits.app.settings

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ExportSettingsRepositoryTest {

    private lateinit var repository: ExportSettingsRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        repository = ExportSettingsRepository(context)
        repository.clearExportFolderUri()
    }

    @Test
    fun testDefaultExportFolderUri_isNullInitially() {
        assertNull(repository.getExportFolderUri())
    }

    @Test
    fun testSaveExportFolderUri_persistsAcrossInstances() {
        val context = RuntimeEnvironment.getApplication()
        val dummyUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMochiExport")

        repository.saveExportFolderUri(dummyUri)

        val retrievedUri = repository.getExportFolderUri()
        assertEquals(dummyUri, retrievedUri)

        // Simulate app restart by creating a new repository instance with the same context
        val newRepositoryInstance = ExportSettingsRepository(context)
        assertEquals(dummyUri, newRepositoryInstance.getExportFolderUri())
    }

    @Test
    fun testClearExportFolderUri_removesSavedUri() {
        val dummyUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMochiExport")
        repository.saveExportFolderUri(dummyUri)

        assertEquals(dummyUri, repository.getExportFolderUri())

        repository.clearExportFolderUri()
        assertNull(repository.getExportFolderUri())
    }

    @Test
    fun testGetFolderName_returnsFallbackPathSegment() {
        val dummyUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMochiExport")
        val folderName = repository.getFolderName(dummyUri)

        assertEquals("primary:MochiExport", folderName)
    }
}

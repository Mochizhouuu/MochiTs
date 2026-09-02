package com.mochits.app.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LaMaModelManagerTest {

    private lateinit var managerSettings: LaMaModelManager
    private lateinit var managerEditor: LaMaModelManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        LaMaModelManager.resetInstanceForTesting()
        managerSettings = LaMaModelManager.getInstance(context)
        managerEditor = LaMaModelManager.getInstance(context)
        managerSettings.deleteModel()
    }

    @Test
    fun testSingletonInstance_isSameAcrossContexts() {
        assertSame(managerSettings, managerEditor)
    }

    @Test
    fun testInitialStatus_isNotDownloaded() {
        assertEquals(LaMaModelStatus.NOT_DOWNLOADED, managerSettings.modelStatus.value)
        assertEquals(LaMaModelStatus.NOT_DOWNLOADED, managerEditor.modelStatus.value)
        assertFalse(managerSettings.isModelDownloaded())
    }

    @Test
    fun testCorruptedFile_detectedAsCorruptedError() {
        val file = managerSettings.getModelFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(1024))

        val status = managerSettings.checkModelStatus()
        assertEquals(LaMaModelStatus.CORRUPTED_ERROR, status)
        assertEquals(LaMaModelStatus.CORRUPTED_ERROR, managerEditor.modelStatus.value)
        assertFalse(managerSettings.isModelDownloaded())

        file.delete()
    }

    @Test
    fun testValidFile_detectedAsDownloaded() {
        val file = managerSettings.getModelFile()
        file.parentFile?.mkdirs()

        val randomAccessFile = java.io.RandomAccessFile(file, "rw")
        randomAccessFile.setLength(55_000_000L)
        randomAccessFile.close()

        val status = managerSettings.checkModelStatus()
        assertEquals(LaMaModelStatus.DOWNLOADED, status)
        assertEquals(LaMaModelStatus.DOWNLOADED, managerEditor.modelStatus.value)
        assertTrue(managerSettings.isModelDownloaded())

        file.delete()
    }

    @Test
    fun testDeleteModel_cleansUpFilesAndUpdatesStatus() {
        val file = managerSettings.getModelFile()
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(100))

        val tempFile = File(file.parentFile, "lama_manga.onnx.tmp")
        tempFile.writeBytes(ByteArray(100))

        managerSettings.deleteModel()

        assertFalse(file.exists())
        assertFalse(tempFile.exists())
        assertEquals(LaMaModelStatus.NOT_DOWNLOADED, managerSettings.modelStatus.value)
        assertEquals(LaMaModelStatus.NOT_DOWNLOADED, managerEditor.modelStatus.value)
    }

    @Test
    fun testTempFileCleanupOnCheckStatusWhenNotDownloading() {
        val targetFile = managerSettings.getModelFile()
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "lama_manga.onnx.tmp")
        tempFile.writeBytes(ByteArray(2048))

        assertTrue(tempFile.exists())
        val status = managerSettings.checkModelStatus()
        assertEquals(LaMaModelStatus.NOT_DOWNLOADED, status)
        assertFalse(tempFile.exists())
    }

    @Test
    fun testLaMaDownloadErrorInfo_formatting() {
        val errorInfo = LaMaDownloadErrorInfo(
            stage = "Evaluasi Status HTTP Response",
            httpCode = 403,
            exceptionType = "IOException",
            exceptionMessage = "Forbidden",
            url = "https://huggingface.co/test"
        )
        val formatted = errorInfo.toFormattedString()
        assertTrue(formatted.contains("Tahap Kegagalan: Evaluasi Status HTTP Response"))
        assertTrue(formatted.contains("HTTP Response Code: 403"))
        assertTrue(formatted.contains("Jenis Exception: IOException"))
        assertTrue(formatted.contains("Pesan Exception: Forbidden"))
        assertTrue(formatted.contains("URL Terakhir: https://huggingface.co/test"))
    }

    @Test
    fun testDownloadModel_safeExecutionWithoutUncaughtCrash() = kotlinx.coroutines.runBlocking {
        // Test that downloadModel executes safely without throwing uncaught exceptions/crashes
        val result = managerSettings.downloadModel()
        if (result) {
            assertTrue(managerSettings.isModelDownloaded())
        } else {
            val errorInfo = managerSettings.lastDownloadError.value
            org.junit.Assert.assertNotNull(errorInfo)
        }
    }

    @Test
    fun testClearLastDownloadError() {
        val manager = managerSettings
        manager.deleteModel()
        assertEquals(null, manager.lastDownloadError.value)
    }
}
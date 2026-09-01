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
}

package com.mochits.app.project

import android.graphics.Bitmap
import com.mochits.app.editor.LayerSerializer
import com.mochits.app.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ProjectBundleTest {

    private fun png(file: File, w: Int, h: Int, color: Int) {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    @Test
    fun `exportImport round trip remaps paths to new id`() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val filesDir = File(ctx.cacheDir, "bundle_t_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val projDir = File(filesDir, "projects/p1").apply { mkdirs() }
            png(File(projDir, "base_image.png"), 8, 8, 0xFFFF0000.toInt())
            val layerFile = File(projDir, "layers/layer_abc.png")
            png(layerFile, 4, 4, 0xFF00FF00.toInt())
            val layersJson = LayerSerializer().serialize(
                listOf(Layer.ImageLayer(id = "abc", name = "I", imagePath = layerFile.absolutePath))
            )
            val entity = ProjectEntity(
                id = "p1", title = "Tes Bundel", width = 8, height = 8,
                createdAt = 1L, updatedAt = 2L, layersJson = layersJson
            )
            val baos = ByteArrayOutputStream()
            assertTrue(ProjectBundle.exportTo(baos, projDir, entity))

            val inserted = mutableListOf<ProjectEntity>()
            val out = ProjectBundle.importFrom(
                baos.toByteArray().inputStream(),
                filesDir,
                readLayers = { LayerSerializer().deserialize(it) },
                writeLayers = { LayerSerializer().serialize(it) },
                insert = { inserted.add(it) }
            )
            assertNotNull(out!!)
            assertNotEquals("p1", out.id)
            assertEquals("Tes Bundel", out.title)
            assertEquals(8, out.width)
            val layers = LayerSerializer().deserialize(out.layersJson)
            assertEquals(1, layers.size)
            val img = layers[0] as Layer.ImageLayer
            assertNotNull(img.imagePath)
            assertTrue(File(img.imagePath!!).exists())
            assertTrue(img.imagePath!!.contains(out.id))
            assertEquals(1, inserted.size)
            assertTrue(File(filesDir, "projects/${out.id}/base_image.png").exists())
        } finally {
            try { filesDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    @Test
    fun `zip slip entry rejected`() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        val filesDir = File(ctx.cacheDir, "bundle_s_${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zip ->
                zip.putNextEntry(ZipEntry("../../evil.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
            val out = ProjectBundle.importFrom(
                baos.toByteArray().inputStream(),
                filesDir,
                readLayers = { LayerSerializer().deserialize(it) },
                writeLayers = { LayerSerializer().serialize(it) },
                insert = {}
            )
            assertNull(out)
            assertFalse(File(filesDir, "evil.txt").exists())
        } finally {
            try { filesDir.deleteRecursively() } catch (_: Exception) {}
        }
    }

    @Test
    fun `fileNameFor sanitizes title`() {
        assertEquals("Halo.mts", ProjectBundle.fileNameFor("Halo"))
        assertEquals("a_b.mts", ProjectBundle.fileNameFor("a/b"))
        assertEquals("proyek.mts", ProjectBundle.fileNameFor("   "))
    }
}

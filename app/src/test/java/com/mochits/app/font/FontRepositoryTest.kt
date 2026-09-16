package com.mochits.app.font

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.File

class FakeCustomFontDao : CustomFontDao {
    private val fontList = mutableListOf<CustomFontEntity>()

    override fun getAllCustomFonts() = flowOf(fontList.toList())

    override suspend fun getAllCustomFontsList(): List<CustomFontEntity> = fontList.toList()

    override suspend fun insertCustomFont(font: CustomFontEntity) {
        fontList.removeAll { it.id == font.id }
        fontList.add(font)
    }

    override suspend fun deleteCustomFont(id: String) {
        fontList.removeAll { it.id == id }
    }
}

@RunWith(RobolectricTestRunner::class)
class FontRepositoryTest {

    private lateinit var context: Context
    private lateinit var customFontDao: FakeCustomFontDao
    private lateinit var fontRepository: FontRepository
    private lateinit var tempDir: File

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        customFontDao = FakeCustomFontDao()
        fontRepository = FontRepository(context, customFontDao)
        tempDir = File(context.filesDir, "custom_fonts")
    }

    @Test
    fun testGetBuiltInFonts_containsDefaultFonts() = runBlocking {
        val builtIn = fontRepository.getBuiltInFonts()
        assertTrue(builtIn.any { it.name == "Default" })
        assertTrue(builtIn.any { it.name == "Sans-Serif" })
        assertTrue(builtIn.any { it.name == "Serif" })
        assertTrue(builtIn.any { it.name == "Monospace" })
    }

    @Test
    fun testGetAllFontsFlow_mergesBuiltInAndCustomFonts() = runBlocking {
        val customEntity = CustomFontEntity(
            id = "font-1",
            displayName = "My Custom Manga Font",
            filePath = "/path/to/custom_font.ttf"
        )
        customFontDao.insertCustomFont(customEntity)

        val allFonts = fontRepository.getAllFontsFlow().first()
        val customItem = allFonts.find { it.name == "My Custom Manga Font" }

        assertTrue(customItem != null)
        assertTrue(customItem?.isCustom == true)
        assertEquals("/path/to/custom_font.ttf", customItem?.filePath)
    }

    @Test
    fun testDeleteCustomFont_removesFileAndDaoRecord() = runBlocking {
        tempDir.mkdirs()
        val dummyFile = File(tempDir, "test_custom_font.ttf").apply { writeText("dummy font data") }
        val fontItem = FontItem(
            name = "Test Font",
            fontNameKey = "Test Font",
            isCustom = true,
            filePath = dummyFile.absolutePath
        )

        val customEntity = CustomFontEntity(
            id = "font-100",
            displayName = "Test Font",
            filePath = dummyFile.absolutePath
        )
        customFontDao.insertCustomFont(customEntity)

        fontRepository.deleteCustomFont(fontItem)

        assertTrue(!dummyFile.exists())
        assertEquals(0, customFontDao.getAllCustomFontsList().size)
    }

    @Test
    fun testImportCustomFont_invalidFileFails() = runBlocking {
        val uri = Uri.parse("content://test/invalid.ttf")
        val result = fontRepository.importCustomFont(uri, "invalid_font.ttf")
        assertTrue(result.isFailure)
    }

    @Test
    fun testFontFavorite_togglePersistsAcrossInstances() {
        assertTrue(fontRepository.favoriteFontKeys.value.isEmpty())

        assertTrue(fontRepository.setFontFavorite("Serif", true))
        assertTrue(fontRepository.favoriteFontKeys.value.contains("Serif"))

        val fresh = FontRepository(context, customFontDao)
        assertTrue(fresh.favoriteFontKeys.value.contains("Serif"))

        assertTrue(fontRepository.setFontFavorite("Serif", false))
        assertTrue(fontRepository.favoriteFontKeys.value.isEmpty())

        assertTrue(!fontRepository.setFontFavorite("   ", true))
    }

    @Test
    fun testDeleteCustomFont_clearsFavorite() = runBlocking {
        tempDir.mkdirs()
        val dummyFile = File(tempDir, "fav_font.ttf").apply { writeText("dummy") }
        val fontItem = FontItem(
            name = "Fav Font",
            fontNameKey = "Fav Font",
            isCustom = true,
            filePath = dummyFile.absolutePath
        )
        customFontDao.insertCustomFont(
            CustomFontEntity(id = "font-fav", displayName = "Fav Font", filePath = dummyFile.absolutePath)
        )
        fontRepository.setFontFavorite("Fav Font", true)
        assertTrue(fontRepository.favoriteFontKeys.value.contains("Fav Font"))

        fontRepository.deleteCustomFont(fontItem)

        assertTrue(!fontRepository.favoriteFontKeys.value.contains("Fav Font"))
    }
}

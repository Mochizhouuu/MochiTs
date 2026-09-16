package com.mochits.app.style

import com.mochits.app.model.TextAlignment
import com.mochits.app.model.TextContainerShape
import com.mochits.app.model.TextStylePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StylePresetRepositoryTest {

    private lateinit var repository: StylePresetRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        repository = StylePresetRepository(context)
    }

    @Test
    fun builtInPresets_areAlwaysLoaded() {
        val presets = repository.presets.value
        assertTrue(presets.size >= 4)
        assertTrue(presets.all { it.name.isNotBlank() })
        assertTrue(presets.any { it.isBuiltIn && it.id == "builtin-dialog" })
    }

    @Test
    fun savePreset_persistsCustomPreset() {
        val saved = repository.savePreset(
            TextStylePreset(
                id = "test-preset-simpan",
                name = "  Style Uji  ",
                fontName = "Serif",
                fontStyle = "Bold",
                alignment = TextAlignment.RIGHT,
                shape = TextContainerShape.OVAL
            )
        )
        assertEquals("Style Uji", saved.name)
        assertFalse(saved.isBuiltIn)

        val found = repository.getPreset("test-preset-simpan")
        assertNotNull(found)
        assertEquals("Serif", found!!.fontName)
        assertEquals("Bold", found.fontStyle)
        assertEquals(TextAlignment.RIGHT, found.alignment)
        assertEquals(TextContainerShape.OVAL, found.shape)

        repository.deletePreset("test-preset-simpan")
    }

    @Test
    fun savePreset_truncatesLongNameAndDefaultsBlank() {
        val long = repository.savePreset(
            TextStylePreset(id = "test-preset-long", name = "A".repeat(100))
        )
        assertTrue(long.name.length <= 40)

        val blank = repository.savePreset(
            TextStylePreset(id = "test-preset-blank", name = "   ")
        )
        assertEquals("Tanpa Nama", blank.name)

        repository.deletePreset("test-preset-long")
        repository.deletePreset("test-preset-blank")
    }

    @Test
    fun deletePreset_removesCustomPreset() {
        repository.savePreset(
            TextStylePreset(id = "test-preset-hapus", name = "Sementara")
        )
        assertTrue(repository.deletePreset("test-preset-hapus"))
        assertEquals(null, repository.getPreset("test-preset-hapus"))

        assertFalse(repository.deletePreset("id-tidak-ada"))
    }

    @Test
    fun deletePreset_hidesBuiltInAndRestoreBringsItBack() {
        assertNotNull(repository.getPreset("builtin-judul"))

        assertTrue(repository.deletePreset("builtin-judul"))
        assertEquals(null, repository.getPreset("builtin-judul"))
        assertTrue(repository.hiddenBuiltInIds.value.contains("builtin-judul"))

        // Pilihan sembunyi bertahan lintas instance.
        val fresh = StylePresetRepository(RuntimeEnvironment.getApplication())
        assertEquals(null, fresh.getPreset("builtin-judul"))

        assertTrue(repository.restoreBuiltInPreset("builtin-judul"))
        assertNotNull(repository.getPreset("builtin-judul"))
        assertFalse(repository.restoreBuiltInPreset("builtin-judul"))
    }

    @Test
    fun customs_surviveAcrossInstances() {
        repository.savePreset(
            TextStylePreset(id = "test-preset-lintas", name = "Lintas Instance")
        )
        val fresh = StylePresetRepository(RuntimeEnvironment.getApplication())
        assertNotNull(fresh.getPreset("test-preset-lintas"))

        repository.deletePreset("test-preset-lintas")
    }

    @Test
    fun exportImport_roundTripsCustomPresets() {
        repository.savePreset(
            TextStylePreset(
                id = "test-preset-ekspor",
                name = "Ekspor Saya",
                fontName = "Serif",
                fontStyle = "Italic",
                alignment = TextAlignment.LEFT,
                shape = TextContainerShape.BOX
            )
        )
        val json = repository.exportCustomsJson()
        assertTrue(json.contains("test-preset-ekspor"))

        repository.deletePreset("test-preset-ekspor")
        assertEquals(null, repository.getPreset("test-preset-ekspor"))

        val imported = repository.importPresetsJson(json)
        assertTrue(imported >= 1)
        val found = repository.getPreset("test-preset-ekspor")
        assertNotNull(found)
        assertEquals("Ekspor Saya", found!!.name)
        assertFalse(found.isBuiltIn)

        repository.deletePreset("test-preset-ekspor")
    }

    @Test
    fun importPresets_rejectsInvalidJson() {
        try {
            repository.importPresetsJson("bukan json{{")
            org.junit.Assert.fail("Seharusnya melempar IllegalArgumentException.")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun pinnedPreset_movesToTopAndSurvivesAcrossInstances() {
        repository.savePreset(
            TextStylePreset(id = "test-preset-pin", name = "Pin Saya")
        )
        val before = repository.presets.value.map { it.id }
        assertTrue(before.indexOf("test-preset-pin") > 0)

        assertTrue(repository.setPresetPinned("test-preset-pin", true))
        assertEquals("test-preset-pin", repository.presets.value.first().id)

        // Yang baru disematkan menempati urutan teratas.
        assertTrue(repository.setPresetPinned("builtin-dialog", true))
        assertEquals("builtin-dialog", repository.presets.value.first().id)
        assertEquals("test-preset-pin", repository.presets.value[1].id)

        val fresh = StylePresetRepository(RuntimeEnvironment.getApplication())
        assertEquals("builtin-dialog", fresh.presets.value.first().id)

        // Lepas pin mengembalikan urutan normal.
        assertTrue(repository.setPresetPinned("test-preset-pin", false))
        assertTrue(repository.setPresetPinned("builtin-dialog", false))
        assertFalse(repository.pinnedPresetIds.value.contains("test-preset-pin"))

        assertFalse(repository.setPresetPinned("id-tidak-ada", true))

        repository.deletePreset("test-preset-pin")
    }
}

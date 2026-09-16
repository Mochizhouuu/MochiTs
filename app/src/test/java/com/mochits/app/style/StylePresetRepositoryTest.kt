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
                name = "  Gaya Uji  ",
                fontName = "Serif",
                fontStyle = "Bold",
                alignment = TextAlignment.RIGHT,
                shape = TextContainerShape.OVAL
            )
        )
        assertEquals("Gaya Uji", saved.name)
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
    fun deletePreset_removesCustomButProtectsBuiltIn() {
        repository.savePreset(
            TextStylePreset(id = "test-preset-hapus", name = "Sementara")
        )
        assertTrue(repository.deletePreset("test-preset-hapus"))
        assertEquals(null, repository.getPreset("test-preset-hapus"))

        assertFalse(repository.deletePreset("builtin-judul"))
        assertNotNull(repository.getPreset("builtin-judul"))
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
}

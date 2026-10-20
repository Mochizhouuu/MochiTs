package com.mochits.app.script

import android.content.Context
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScriptMappingRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mochits_script_mapping", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun testDefaults_markDoubleSlashSymbolsAsLinked() {
        val defaults = ScriptMappingRepository.defaultMappings()
        val dialog = defaults.first { it.symbol == "//-" }
        val box = defaults.first { it.symbol == "//[]" }
        assertTrue(dialog.linked)
        assertFalse(dialog.append)
        assertTrue(box.linked)
        assertFalse(box.append)
    }

    @Test
    fun testLegacyAppendMapping_migratesBuiltInDoubleSlashToLinked() {
        val legacy = listOf(
            ScriptSymbolEntry("//-", presetId = "p1", append = true),
            ScriptSymbolEntry("//[]", presetId = "", append = true)
        )
        context.getSharedPreferences("mochits_script_mapping", Context.MODE_PRIVATE)
            .edit()
            .putString("symbol_mappings_json", Gson().toJson(legacy))
            .commit()

        val repo = ScriptMappingRepository(context)
        val dialog = repo.getMappings().first { it.symbol == "//-" }
        assertEquals("p1", dialog.presetId)
        assertTrue(dialog.linked)
        assertFalse(dialog.append)
        assertTrue(repo.getMappings().first { it.symbol == "//[]" }.linked)
    }

    @Test
    fun testSetMappings_normalizesExclusiveFlags() {
        val repo = ScriptMappingRepository(context)
        repo.setMappings(
            listOf(
                ScriptSymbolEntry("//-", append = true, linked = true),
                ScriptSymbolEntry("~", append = true)
            )
        )
        val dialog = repo.getMappings().first { it.symbol == "//-" }
        assertTrue(dialog.linked)
        assertFalse(dialog.append)
        assertTrue(repo.getMappings().first { it.symbol == "~" }.append)
    }
}

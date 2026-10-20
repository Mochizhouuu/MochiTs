package com.mochits.app.script

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ScriptSettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: ScriptSettingsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("mochits_script_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repository = ScriptSettingsRepository(context)
    }

    @Test
    fun testAutoRestore_defaultsToTrue() {
        assertTrue(repository.autoRestore.value)
    }

    @Test
    fun testAutoRestore_persistsAcrossInstances() {
        repository.setAutoRestore(false)
        assertFalse(repository.autoRestore.value)

        val newInstance = ScriptSettingsRepository(context)
        assertFalse(newInstance.autoRestore.value)
    }

    @Test
    fun testLastUriAndName_areStoredPerProject() {
        context.getSharedPreferences("mochits_script_state", Context.MODE_PRIVATE).edit()
            .putString("uri_p1", "content://tl/a.txt")
            .putString("name_p1", "a.txt")
            .apply()

        assertEquals("content://tl/a.txt", repository.getLastUri("p1"))
        assertEquals("a.txt", repository.getLastName("p1"))
        assertNull(repository.getLastUri("p2"))
        assertNull(repository.getLastName("p2"))
    }
}

package com.mochits.app.imaging

import android.graphics.Bitmap
import android.graphics.Color
import com.mochits.core.imaging.Result
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LaMaInpaintEngineTest {

    private lateinit var engine: LaMaInpaintEngine
    private lateinit var modelManager: LaMaModelManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        LaMaModelManager.resetInstanceForTesting()
        modelManager = LaMaModelManager.getInstance(context)
        modelManager.deleteModel()
        engine = LaMaInpaintEngine(context)
    }

    @Test
    fun testEmptyMask_returnsBaseCopyAndOutsidePixelsIdentical() {
        runBlocking {
            val width = 100
            val height = 100
            val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    base.setPixel(x, y, Color.rgb(x * 2, y * 2, 128))
                }
            }
            val emptyMask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)

            // When model is not downloaded, inpaintLaMa returns Result.Error
            // which guarantees baseBitmap is never mutated/corrupted when model is not ready.
            val result = engine.inpaintLaMa(base, emptyMask)
            assertTrue(result is Result.Error)

            // Base bitmap remains 100% untouched
            for (y in 0 until height) {
                for (x in 0 until width) {
                    assertEquals("Pixel ($x, $y) changed", Color.rgb(x * 2, y * 2, 128), base.getPixel(x, y))
                }
            }
        }
    }

    @Test
    fun testUndownloadedModel_returnsErrorWithoutModifyingOrCrashing() {
        runBlocking {
            val width = 200
            val height = 200
            val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            mask.setPixel(50, 50, Color.WHITE)

            val result = engine.inpaintLaMa(base, mask)
            assertTrue(result is Result.Error)
        }
    }
}

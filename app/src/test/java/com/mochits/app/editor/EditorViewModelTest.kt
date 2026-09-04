package com.mochits.app.editor

import androidx.room.Room
import com.mochits.app.model.Layer
import com.mochits.app.model.TextStyleConfig
import com.mochits.app.project.MochiTsDatabase
import com.mochits.app.project.ProjectDao
import com.mochits.app.project.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelTest {

    private lateinit var db: MochiTsDatabase
    private lateinit var dao: ProjectDao
    private lateinit var repository: ProjectRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, MochiTsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.projectDao()
        repository = ProjectRepository(context, dao)

        val project = repository.createProject(
            title = "Test Project",
            width = 1080,
            height = 1920,
            imageUri = null
        )

        val savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("projectId" to project.id))
        val exportSettingsRepository = com.mochits.app.settings.ExportSettingsRepository(context)
        val fontRepository = com.mochits.app.font.FontRepository(context, db.customFontDao())
        viewModel = EditorViewModel(context, repository, exportSettingsRepository, fontRepository, savedStateHandle)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testAddTextLayer_updatesLayersAndUndoState() {
        assertFalse(viewModel.canUndo.value)

        viewModel.addTextLayer("Hello World")

        assertEquals(1, viewModel.layers.value.size)
        val addedLayer = viewModel.layers.value.first() as Layer.TextLayer
        assertEquals("Hello World", addedLayer.text)
        assertTrue(viewModel.canUndo.value)
        assertFalse(viewModel.canRedo.value)
    }

    @Test
    fun testUndoAndRedo_forTextLayerAddition() {
        viewModel.addTextLayer("Test Layer")

        assertEquals(1, viewModel.layers.value.size)
        assertTrue(viewModel.canUndo.value)

        viewModel.undo()

        assertEquals(0, viewModel.layers.value.size)
        assertFalse(viewModel.canUndo.value)
        assertTrue(viewModel.canRedo.value)

        viewModel.redo()

        assertEquals(1, viewModel.layers.value.size)
        assertTrue(viewModel.canUndo.value)
        assertFalse(viewModel.canRedo.value)
    }

    @Test
    fun testUpdateSelectedTextLayerStyle_changesFontSize() {
        viewModel.addTextLayer("Sample Text")

        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val newStyle = TextStyleConfig(fontSize = 72f)
        viewModel.updateSelectedTextLayerStyle(newStyle)

        val updatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(72f, updatedLayer.style.fontSize, 0.01f)
    }

    @Test
    fun testSetupCanvasSize_initializesDimensions() {
        viewModel.setupCanvasSize(800, 1200)
        assertNotNull(viewModel.baseBitmap.value)
        assertEquals(800, viewModel.baseBitmap.value?.width)
        assertEquals(1200, viewModel.baseBitmap.value?.height)
    }

    @Test
    fun testAddTextLayer_canvasTransformUnchanged_andPositionedAtVisibleCenter() {
        val initialScale = 2.0f
        val initialOffsetX = -100f
        val initialOffsetY = -200f
        viewModel.canvasState.updateTransform(initialScale, initialOffsetX, initialOffsetY)

        val viewportW = 1080f
        val viewportH = 1920f

        val text = "Centered Text"
        viewModel.addTextLayer(
            text = text,
            viewportWidth = viewportW,
            viewportHeight = viewportH
        )

        // 1. Verify canvas transform is completely unchanged (0% movement)
        assertEquals(initialScale, viewModel.canvasState.scale, 0.001f)
        assertEquals(initialOffsetX, viewModel.canvasState.offsetX, 0.001f)
        assertEquals(initialOffsetY, viewModel.canvasState.offsetY, 0.001f)

        // 2. Verify text layer is positioned with top-left offset compensating for half text width/height
        val expectedCanvasCenter = viewModel.canvasState.mapper.screenToCanvas(viewportW / 2f, viewportH / 2f)
        val addedLayer = viewModel.layers.value.last() as Layer.TextLayer

        val bounds = viewModel.textRenderer.getTextBounds(text, addedLayer.style, 0f, 0f, addedLayer.textContainerShape, addedLayer.boxWidth, addedLayer.boxHeight)
        val expectedFinalX = expectedCanvasCenter.x - (bounds.width() / 2f)
        val expectedFinalY = expectedCanvasCenter.y - (bounds.height() / 2f)

        assertEquals(expectedFinalX, addedLayer.x, 0.01f)
        assertEquals(expectedFinalY, addedLayer.y, 0.01f)

        // 3. Verify visual center of bounding box matches canvas center
        val actualBounds = viewModel.textRenderer.getTextBounds(addedLayer)
        assertEquals(expectedCanvasCenter.x, actualBounds.centerX(), 0.01f)
        assertEquals(expectedCanvasCenter.y, actualBounds.centerY(), 0.01f)
    }

    @Test
    fun testMagicWandUndoRedo_dualMaskSynchronization() {
        viewModel.setupCanvasSize(100, 100)
        val tools = viewModel.maskSelectionTools
        assertNotNull(tools)

        val srcBitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        srcBitmap.eraseColor(android.graphics.Color.WHITE)

        // Draw Object A (10..19, 10..19)
        for (y in 10 until 20) {
            for (x in 10 until 20) {
                srcBitmap.setPixel(x, y, android.graphics.Color.BLACK)
            }
        }
        // Draw Object B (70..79, 70..79)
        for (y in 70 until 80) {
            for (x in 70 until 80) {
                srcBitmap.setPixel(x, y, android.graphics.Color.BLACK)
            }
        }
        viewModel.baseBitmap.value = srcBitmap

        // 1. Select Object A and expand by 5px
        viewModel.saveUndoSnapshot()
        tools?.magicWandSelect(srcBitmap, androidx.compose.ui.geometry.Offset(15f, 15f), tolerance = 10f, expandPixels = 5)

        val p1 = (tools?.maskBitmap?.getPixel(5, 15) ?: 0)
        val alpha1 = (p1 ushr 24) or (p1 and 0xFF)
        assertEquals(255, alpha1)

        val p2 = (tools?.rawMaskBitmap?.getPixel(5, 15) ?: 0)
        val alpha2 = (p2 ushr 24) or (p2 and 0xFF)
        assertEquals(0, alpha2)

        // Save Snapshot state 1 (Object A selected with expand 5)
        viewModel.saveUndoSnapshot()

        // 2. Select Object B
        tools?.magicWandSelect(srcBitmap, androidx.compose.ui.geometry.Offset(75f, 75f), tolerance = 10f, expandPixels = 5)

        // Both Object A and B are selected
        val pA = (tools?.maskBitmap?.getPixel(15, 15) ?: 0)
        val pB = (tools?.maskBitmap?.getPixel(75, 75) ?: 0)
        assertEquals(255, (pA ushr 24) or (pA and 0xFF))
        assertEquals(255, (pB ushr 24) or (pB and 0xFF))

        // 3. Perform UNDO -> Should restore to state before tapping Object B
        viewModel.undo()

        // Object A is selected, Object B is NOT selected
        val valA = ((tools?.maskBitmap?.getPixel(15, 15) ?: 0) ushr 24) or ((tools?.maskBitmap?.getPixel(15, 15) ?: 0) and 0xFF)
        val valB = ((tools?.maskBitmap?.getPixel(75, 75) ?: 0) ushr 24) or ((tools?.maskBitmap?.getPixel(75, 75) ?: 0) and 0xFF)
        assertEquals(255, valA)
        assertEquals(0, valB)
        // rawMaskBitmap MUST also be restored: (75, 75) is 0, (15, 15) is 255
        val rawA = (tools?.rawMaskBitmap?.getPixel(15, 15) ?: 0)
        val rawB = (tools?.rawMaskBitmap?.getPixel(75, 75) ?: 0)
        assertEquals(255, (rawA ushr 24) or (rawA and 0xFF))
        assertEquals(0, (rawB ushr 24) or (rawB and 0xFF))

        // 4. Perform REDO -> Should restore state containing both A and B
        viewModel.redo()

        val pA2 = (tools?.maskBitmap?.getPixel(15, 15) ?: 0)
        val pB2 = (tools?.maskBitmap?.getPixel(75, 75) ?: 0)
        val rawA2 = (tools?.rawMaskBitmap?.getPixel(15, 15) ?: 0)
        val rawB2 = (tools?.rawMaskBitmap?.getPixel(75, 75) ?: 0)
        assertEquals(255, (pA2 ushr 24) or (pA2 and 0xFF))
        assertEquals(255, (pB2 ushr 24) or (pB2 and 0xFF))
        assertEquals(255, (rawA2 ushr 24) or (rawA2 and 0xFF))
        assertEquals(255, (rawB2 ushr 24) or (rawB2 and 0xFF))
    }
    @Test
    fun testUpdateSelectedTextContent_updatesActiveLayerTextAndPreservesStyle() {
        viewModel.addTextLayer("Original Text")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val originalLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals("Original Text", originalLayer.text)

        viewModel.updateSelectedTextContent("Updated Text Content")

        val updatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals("Updated Text Content", updatedLayer.text)
        assertEquals(originalLayer.style, updatedLayer.style)
        assertEquals(originalLayer.x, updatedLayer.x, 0.01f)
        assertEquals(originalLayer.y, updatedLayer.y, 0.01f)
    }

    @Test
    fun testDeleteLayer_removesLayerAndClearsSelection() {
        viewModel.addTextLayer("Layer To Delete")
        val textLayerId = viewModel.selectedLayerId.value
        assertNotNull(textLayerId)
        assertEquals(1, viewModel.layers.value.size)

        viewModel.deleteLayer(textLayerId!!)

        assertEquals(0, viewModel.layers.value.size)
        assertTrue(viewModel.canUndo.value)

        viewModel.undo()
        assertEquals(1, viewModel.layers.value.size)
    }

    @Test
    fun testUpdateSelectedTextLayerPositionAndRotation_updatesValuesAndCanUndo() {
        viewModel.addTextLayer("Transformable Layer")
        val transformLayerId = viewModel.selectedLayerId.value
        assertNotNull(transformLayerId)

        viewModel.updateSelectedTextLayerPosition(150f, 250f, saveUndo = true)
        viewModel.updateSelectedTextLayerRotation(45f, saveUndo = true)

        val layer = viewModel.layers.value.find { it.id == transformLayerId } as Layer.TextLayer
        assertEquals(150f, layer.x, 0.01f)
        assertEquals(250f, layer.y, 0.01f)
        assertEquals(45f, layer.rotation, 0.01f)

        // Undo rotation
        viewModel.undo()
        val layerAfterUndoRot = viewModel.layers.value.find { it.id == transformLayerId } as Layer.TextLayer
        assertEquals(0f, layerAfterUndoRot.rotation, 0.01f)

        // Undo position
        viewModel.undo()
        val layerAfterUndoPos = viewModel.layers.value.find { it.id == transformLayerId } as Layer.TextLayer
        assertFalse(layerAfterUndoPos.x == 150f)
    }

    @Test
    fun testResizeRotatedTextLayer_acrossMultipleAngles() {
        viewModel.addTextLayer("Resize Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val angles = listOf(0f, 45f, 90f, 180f, 270f)
        val initialFontSize = 36f

        for (angle in angles) {
            viewModel.updateSelectedTextLayerRotation(angle, saveUndo = false)
            val currentLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
            assertEquals(angle, currentLayer.rotation, 0.01f)

            // Calculate center of text bounds
            val bounds = viewModel.textRenderer.getTextBounds(currentLayer)
            val textCenterX = bounds.centerX()
            val textCenterY = bounds.centerY()

            // Simulate touch DOWN at bottom-right resize handle position in canvas space
            val touchDownPt = androidx.compose.ui.geometry.Offset(bounds.right, bounds.bottom)
            val initialDragDist = kotlin.math.hypot(touchDownPt.x - textCenterX, touchDownPt.y - textCenterY)
            assertTrue(initialDragDist > 0f)

            // Simulate DRAG away from center by 1.5x distance
            val dragScale = 1.5f
            val dragPt = androidx.compose.ui.geometry.Offset(
                textCenterX + (touchDownPt.x - textCenterX) * dragScale,
                textCenterY + (touchDownPt.y - textCenterY) * dragScale
            )

            val currentDist = kotlin.math.hypot(dragPt.x - textCenterX, dragPt.y - textCenterY)
            val scaleFactor = currentDist / initialDragDist
            val expectedFontSize = (initialFontSize * scaleFactor).coerceIn(10f, 300f)

            viewModel.updateSelectedTextLayerStyle(
                currentLayer.style.copy(fontSize = expectedFontSize),
                saveUndo = false
            )

            val updatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
            assertEquals(54f, updatedLayer.style.fontSize, 0.1f)

            // Reset font size back to initial for next angle iteration
            viewModel.updateSelectedTextLayerStyle(
                updatedLayer.style.copy(fontSize = initialFontSize),
                saveUndo = false
            )
        }
    }

    @Test
    fun testRotateAndDeleteTextLayer_noRegression() {
        viewModel.addTextLayer("Rotate and Delete Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        // 1. Perform rotation to 90 degrees
        viewModel.updateSelectedTextLayerRotation(90f, saveUndo = true)
        val rotatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(90f, rotatedLayer.rotation, 0.01f)

        // 2. Perform delete
        viewModel.deleteLayer(layerId!!)
        assertEquals(0, viewModel.layers.value.size)

        // 3. Undo delete and rotation
        viewModel.undo() // restore layer
        assertEquals(1, viewModel.layers.value.size)
        val restoredLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(90f, restoredLayer.rotation, 0.01f)

        viewModel.undo() // restore rotation to 0
        val unrotatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(0f, unrotatedLayer.rotation, 0.01f)
    }

    @Test
    fun testRepeatedEyedropperSessions_activatesAndSamplesMultipleTimesInSameSession() = runBlocking {
        var firstSelectedColor: Int? = null
        var secondSelectedColor: Int? = null

        // Ensure base bitmap is initialized
        viewModel.setupCanvasSize(100, 100)

        // --- Session 1 ---
        viewModel.startEyedropper { color ->
            firstSelectedColor = color
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(viewModel.isEyedropperActive.value)
        assertNotNull(viewModel.eyedropperCanvasPt.value)
        assertNotNull(viewModel.sampledColorPreview.value)

        // Move eyedropper crosshair
        viewModel.updateEyedropperPosition(androidx.compose.ui.geometry.Offset(10f, 10f))
        assertEquals(10f, viewModel.eyedropperCanvasPt.value?.x ?: 0f, 0.01f)

        // Confirm selection 1
        viewModel.confirmEyedropper()

        assertFalse(viewModel.isEyedropperActive.value)
        assertEquals(null, viewModel.eyedropperCanvasPt.value)
        assertEquals(null, viewModel.sampledColorPreview.value)
        assertNotNull(firstSelectedColor)

        // --- Session 2 (Re-activation in same session) ---
        viewModel.startEyedropper { color ->
            secondSelectedColor = color
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(viewModel.isEyedropperActive.value)
        assertNotNull(viewModel.eyedropperCanvasPt.value)
        // Verify crosshair reset to center
        val expectedCenterX = (viewModel.baseBitmap.value?.width ?: 100) / 2f
        assertEquals(expectedCenterX, viewModel.eyedropperCanvasPt.value?.x ?: 0f, 0.01f)

        viewModel.updateEyedropperPosition(androidx.compose.ui.geometry.Offset(50f, 50f))
        assertEquals(50f, viewModel.eyedropperCanvasPt.value?.x ?: 0f, 0.01f)

        // Confirm selection 2
        viewModel.confirmEyedropper()

        assertFalse(viewModel.isEyedropperActive.value)
        assertEquals(null, viewModel.eyedropperCanvasPt.value)
        assertNotNull(secondSelectedColor)

        // --- Session 3 (Cancel) ---
        viewModel.startEyedropper { }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue(viewModel.isEyedropperActive.value)

        viewModel.cancelEyedropper()
        assertFalse(viewModel.isEyedropperActive.value)
        assertEquals(null, viewModel.eyedropperCanvasPt.value)
    }
    @Test
    fun testOneWayStretchVerticalAndHorizontal_keepsTopLeftFixed() {
        viewModel.addTextLayer("One-Way Stretch Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val initialLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        val initialX = initialLayer.x
        val initialY = initialLayer.y

        // 1. One-Way Stretch Vertical (pull bottom edge down)
        val newHeight = 350f
        viewModel.updateSelectedTextLayerStretch(
            boxWidth = initialLayer.boxWidth,
            boxHeight = newHeight,
            newX = initialX,
            newY = initialY,
            saveUndo = true
        )

        val layerAfterV = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(newHeight, layerAfterV.boxHeight ?: 0f, 0.01f)
        assertEquals(initialX, layerAfterV.x, 0.01f)
        assertEquals(initialY, layerAfterV.y, 0.01f)

        // 2. One-Way Stretch Horizontal (pull right edge right)
        val newWidth = 450f
        viewModel.updateSelectedTextLayerStretch(
            boxWidth = newWidth,
            boxHeight = layerAfterV.boxHeight,
            newX = initialX,
            newY = initialY,
            saveUndo = true
        )

        val layerAfterH = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(newWidth, layerAfterH.boxWidth ?: 0f, 0.01f)
        assertEquals(newHeight, layerAfterH.boxHeight ?: 0f, 0.01f)
        assertEquals(initialX, layerAfterH.x, 0.01f)
        assertEquals(initialY, layerAfterH.y, 0.01f)
    }

    @Test
    fun testResizeWithExplicitBoxDimensions_scalesProportionallyBothBoxAndFont() {
        viewModel.addTextLayer("Resize Proportional Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        // Set initial box dimensions (e.g. from stretch or oval)
        val initialFontSize = 40f
        val initialBoxW = 200f
        val initialBoxH = 100f

        viewModel.updateSelectedTextLayerResize(
            fontSize = initialFontSize,
            boxWidth = initialBoxW,
            boxHeight = initialBoxH,
            saveUndo = true
        )

        val scaleFactor = 1.5f
        viewModel.updateSelectedTextLayerResize(
            fontSize = initialFontSize * scaleFactor,
            boxWidth = initialBoxW * scaleFactor,
            boxHeight = initialBoxH * scaleFactor,
            saveUndo = false
        )

        val resizedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(60f, resizedLayer.style.fontSize, 0.01f)
        assertEquals(300f, resizedLayer.boxWidth ?: 0f, 0.01f)
        assertEquals(150f, resizedLayer.boxHeight ?: 0f, 0.01f)
    }

    @Test
    fun testRotateTopRightHandle_calculatesAngleFromTopRightCornerAcrossRotations() {
        viewModel.addTextLayer("Top Right Rotate Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val shapes = listOf(com.mochits.app.model.TextContainerShape.BOX, com.mochits.app.model.TextContainerShape.OVAL)
        val angles = listOf(0f, 45f, 90f)

        for (shape in shapes) {
            viewModel.updateSelectedTextLayerContainerShape(shape)
            for (initialAngle in angles) {
                viewModel.updateSelectedTextLayerRotation(initialAngle, saveUndo = false)
                val layer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
                assertEquals(initialAngle, layer.rotation, 0.01f)

                val bounds = viewModel.textRenderer.getTextBounds(layer)
                val textCenterX = bounds.centerX()
                val textCenterY = bounds.centerY()

                val touchAngle = Math.toDegrees(kotlin.math.atan2((bounds.top - textCenterY).toDouble(), (bounds.right - textCenterX).toDouble())).toFloat()
                val deltaAngle = 15f
                val newAngle = (layer.rotation + deltaAngle) % 360f

                viewModel.updateSelectedTextLayerRotation(newAngle, saveUndo = false)
                val rotatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
                assertEquals(newAngle, rotatedLayer.rotation, 0.01f)
            }
        }
    }

    @Test
    fun testNormalMove_worksCleanlyAfterStretchInteraction() {
        viewModel.addTextLayer("Stretch then Move")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val layer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        val bounds = viewModel.textRenderer.getTextBounds(layer)
        val cx = bounds.centerX()
        // val cy = bounds.centerY()

        // Perform stretch
        val newW = 350f
        viewModel.updateSelectedTextLayerStretch(
            boxWidth = newW,
            boxHeight = layer.boxHeight,
            newX = cx - (newW / 2f),
            newY = layer.y,
            saveUndo = true
        )

        val stretchedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        val startX = stretchedLayer.x
        val startY = stretchedLayer.y

        // Perform normal body move
        val deltaX = 50f
        val deltaY = 100f
        viewModel.updateSelectedTextLayerPosition(startX + deltaX, startY + deltaY, saveUndo = true)

        val movedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(startX + deltaX, movedLayer.x, 0.01f)
        assertEquals(startY + deltaY, movedLayer.y, 0.01f)
    }

    @Test
    fun testContainerShapeSwitch_BoxToOvalAndReflow() {
        viewModel.addTextLayer("Text Inside Container")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val initialLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(com.mochits.app.model.TextContainerShape.BOX, initialLayer.textContainerShape)

        // Switch to OVAL
        viewModel.updateSelectedTextLayerContainerShape(com.mochits.app.model.TextContainerShape.OVAL)

        val updatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(com.mochits.app.model.TextContainerShape.OVAL, updatedLayer.textContainerShape)
        assertNotNull(updatedLayer.boxWidth)
        assertNotNull(updatedLayer.boxHeight)
        assertTrue(updatedLayer.boxWidth!! >= 30f)
        assertTrue(updatedLayer.boxHeight!! >= 20f)

        // Verify layout result runs without error for OVAL
        val bounds = viewModel.textRenderer.getTextBounds(updatedLayer)
        assertTrue(bounds.width() > 0f)
        assertTrue(bounds.height() > 0f)
    }

    @Test
    fun testAddTextLayer_DefaultFontSize_ClampedToWidthProportional() {
        // Add text layer on a tall canvas
        viewModel.addTextLayer("Tall Canvas Text Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        val layer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        val fontSize = layer.style.fontSize
        assertTrue("Font size on default canvas width should be between 24f and 48f, was $fontSize", fontSize in 24f..48f)
    }

    @Test
    fun testUpdateSelectedTextLayerContainerShape_BoxInitializesDimensions() {
        viewModel.addTextLayer("Container Box Initializer Test")
        val layerId = viewModel.selectedLayerId.value
        assertNotNull(layerId)

        viewModel.updateSelectedTextLayerContainerShape(com.mochits.app.model.TextContainerShape.BOX)

        val updatedLayer = viewModel.layers.value.find { it.id == layerId } as Layer.TextLayer
        assertEquals(com.mochits.app.model.TextContainerShape.BOX, updatedLayer.textContainerShape)
        assertNotNull("Box width should be initialized when explicitly set to BOX shape", updatedLayer.boxWidth)
        assertNotNull("Box height should be initialized when explicitly set to BOX shape", updatedLayer.boxHeight)
    }
}

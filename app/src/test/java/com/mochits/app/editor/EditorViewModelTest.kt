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
        viewModel = EditorViewModel(context, repository, savedStateHandle)
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

        val bounds = viewModel.textRenderer.getTextBounds(text, addedLayer.style, 0f, 0f)
        val expectedFinalX = expectedCanvasCenter.x - (bounds.width() / 2f)
        val expectedFinalY = expectedCanvasCenter.y - (bounds.height() / 2f)

        assertEquals(expectedFinalX, addedLayer.x, 0.01f)
        assertEquals(expectedFinalY, addedLayer.y, 0.01f)

        // 3. Verify visual center of bounding box matches canvas center
        val actualBounds = viewModel.textRenderer.getTextBounds(addedLayer.text, addedLayer.style, addedLayer.x, addedLayer.y)
        assertEquals(expectedCanvasCenter.x, actualBounds.centerX(), 0.01f)
        assertEquals(expectedCanvasCenter.y, actualBounds.centerY(), 0.01f)
    }
}

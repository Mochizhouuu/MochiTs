package com.mochits.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Canvas non-destruktif: base image ditampilkan via [imageContent] yang
 * disuntikkan dari luar module, mendukung pinch-zoom & pan, dan text
 * layer yang bisa di-drag posisinya.
 *
 * Drag teks memakai state.scale TERKINI (via rememberUpdatedState) agar
 * delta gerakan jari selalu dikonversi dengan faktor zoom yang benar
 * saat itu juga, bukan nilai scale basi dari komposisi sebelumnya.
 */
@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    state.onTransform(zoomChange, panChange)
                }
            }
            .onGloballyPositioned { canvasSize = it.size }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offset.x,
                    translationY = state.offset.y
                )
        ) {
            imageContent(state.baseImagePath, ContentScale.Fit, Modifier.fillMaxSize())

            state.textLayers.forEach { layer ->
                DraggableTextLayer(
                    layer = layer,
                    canvasSize = canvasSize,
                    currentScale = state.scale,
                    isSelected = state.selectedLayerId == layer.id,
                    onSelect = { state.selectLayer(layer.id) },
                    onDragBy = { deltaX, deltaY ->
                        val width = canvasSize.width.coerceAtLeast(1)
                        val height = canvasSize.height.coerceAtLeast(1)
                        val newX = layer.relativeX + deltaX / width
                        val newY = layer.relativeY + deltaY / height
                        state.updateLayerPosition(layer.id, newX, newY)
                    }
                )
            }
        }
    }
}

@Composable
private fun DraggableTextLayer(
    layer: CanvasTextLayer,
    canvasSize: IntSize,
    currentScale: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDragBy: (deltaX: Float, deltaY: Float) -> Unit
) {
    val offsetX = layer.relativeX * canvasSize.width
    val offsetY = layer.relativeY * canvasSize.height

    // Selalu baca nilai scale TERBARU di dalam callback gesture (yang
    // dibuat sekali oleh pointerInput), tanpa perlu me-restart gesture
    // detector tiap scale berubah.
    val scaleState = rememberUpdatedState(currentScale)

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .padding(8.dp)
            .background(
                if (isSelected) Color.Black.copy(alpha = 0.15f) else Color.Transparent
            )
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = { onSelect() }
                ) { change, dragAmount ->
                    change.consume()
                    val scale = scaleState.value.coerceAtLeast(0.01f)
                    onDragBy(dragAmount.x / scale, dragAmount.y / scale)
                }
            }
    ) {
        BasicText(
            text = layer.text,
            style = TextStyle(fontSize = layer.fontSizeSp.sp, color = Color.Black)
        )
    }
}

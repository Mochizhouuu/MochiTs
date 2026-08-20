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
 * disuntikkan dari luar module (agar :core-canvas tidak perlu tahu cara
 * loading gambar — dependency ke image loader tetap di :app), mendukung
 * pinch-zoom & pan, dan text layer yang bisa di-drag posisinya.
 *
 * Gesture pinch-zoom/pan dipasang di Box PALING LUAR, sedangkan drag
 * per-teks dipasang di Box TERDALAM (child) dengan area sentuh yang
 * diperluas via padding — Compose memprioritaskan pointer input pada
 * node anak sebelum diteruskan ke induk, sehingga drag teks tidak lagi
 * "kalah" oleh gesture pinch-zoom canvas.
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
                    isSelected = state.selectedLayerId == layer.id,
                    onSelect = { state.selectLayer(layer.id) },
                    onDrag = { deltaX, deltaY ->
                        val width = canvasSize.width.coerceAtLeast(1)
                        val height = canvasSize.height.coerceAtLeast(1)
                        // dragAmount sudah dalam skala layar asli; dibagi state.scale
                        // supaya kecepatan geser konsisten meski canvas sedang di-zoom.
                        val newX = layer.relativeX + (deltaX / state.scale) / width
                        val newY = layer.relativeY + (deltaY / state.scale) / height
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
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDrag: (deltaX: Float, deltaY: Float) -> Unit
) {
    val offsetX = layer.relativeX * canvasSize.width
    val offsetY = layer.relativeY * canvasSize.height

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            // Area sentuh diperluas via padding (bukan cuma sebesar teksnya)
            // agar jari mudah "mengenai" teks kecil sekalipun.
            .padding(8.dp)
            .background(
                if (isSelected) Color.Black.copy(alpha = 0.15f) else Color.Transparent
            )
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = { onSelect() }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        BasicText(
            text = layer.text,
            style = TextStyle(fontSize = layer.fontSizeSp.sp, color = Color.Black)
        )
    }
}

package com.mochits.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Canvas untuk gambar long-strip (webtoon): gambar ditampilkan dalam
 * ukuran (intrinsicSize * scale) sesungguhnya di dalam area yang bisa
 * di-scroll vertikal secara natural — BUKAN via graphicsLayer translate
 * manual. Ini penting karena scroll bawaan (Modifier.verticalScroll)
 * jauh lebih stabil untuk konten yang jauh lebih tinggi dari layar,
 * dan tidak konflik dengan gesture drag teks di atasnya.
 *
 * Posisi teks disimpan dalam piksel GAMBAR ASLI (lihat
 * [CanvasEditorState]) lalu dikonversi ke piksel LAYAR dengan
 * mengalikan scale saat digambar — sehingga teks selalu tepat
 * menempel ke bagian gambar yang dimaksud, di zoom level berapa pun.
 */
@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    val displayWidthDp = with(androidx.compose.ui.platform.LocalDensity.current) {
        (state.intrinsicWidthPx * state.scale).toDp()
    }
    val displayHeightDp = with(androidx.compose.ui.platform.LocalDensity.current) {
        (state.intrinsicHeightPx * state.scale).toDp()
    }

    Box(
        modifier = modifier
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    state.setScale(state.scale * zoomChange)
                }
            }
    ) {
        Box(
            modifier = Modifier.size(width = displayWidthDp, height = displayHeightDp)
        ) {
            imageContent(
                state.baseImagePath,
                ContentScale.FillBounds,
                Modifier.fillMaxSize()
            )

            state.textLayers.forEach { layer ->
                DraggableTextLayer(
                    layer = layer,
                    scale = state.scale,
                    isSelected = state.selectedLayerId == layer.id,
                    onSelect = { state.selectLayer(layer.id) },
                    onDragBy = { deltaXPx, deltaYPx ->
                        // deltaXPx/deltaYPx dalam piksel LAYAR -> konversi ke
                        // piksel GAMBAR ASLI dengan membagi scale saat ini.
                        state.moveLayerBy(
                            layer.id,
                            deltaXPx / state.scale,
                            deltaYPx / state.scale
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DraggableTextLayer(
    layer: CanvasTextLayer,
    scale: Float,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDragBy: (deltaXPx: Float, deltaYPx: Float) -> Unit
) {
    // Posisi LAYAR = posisi GAMBAR ASLI * scale saat ini.
    val screenX = layer.xInImagePx * scale
    val screenY = layer.yInImagePx * scale
    val scaleState = rememberUpdatedState(scale)

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .padding(8.dp)
            .background(
                if (isSelected) Color.Black.copy(alpha = 0.15f) else Color.Transparent
            )
            .pointerInput(layer.id) {
                detectDragGestures(onDragStart = { onSelect() }) { change, dragAmount ->
                    change.consume()
                    onDragBy(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        BasicText(
            text = layer.text,
            style = TextStyle(fontSize = (layer.fontSizeSp * scaleState.value).sp, color = Color.Black)
        )
    }
}

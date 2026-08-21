package com.mochits.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Canvas untuk gambar long-strip (webtoon): gambar ditampilkan dalam
 * ukuran (intrinsicSize * scale) sesungguhnya di dalam area yang bisa
 * di-scroll vertikal secara natural.
 *
 * KUNCI: pinch-zoom HANYA diproses saat pointer count >= 2 (dua jari),
 * dan TIDAK meng-consume apa pun saat cuma 1 jari — sehingga
 * Modifier.verticalScroll/horizontalScroll bawaan tetap bisa
 * memproses drag satu jari secara normal. Sebelumnya gesture zoom
 * memakai detectTransformGestures yang menyerap SEMUA pointer event
 * (termasuk 1 jari), sehingga scroll tidak pernah berjalan.
 */
@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()
    val density = LocalDensity.current

    val displayWidthDp = with(density) { (state.intrinsicWidthPx * state.scale).toDp() }
    val displayHeightDp = with(density) { (state.intrinsicHeightPx * state.scale).toDp() }

    Box(
        modifier = modifier
            .verticalScroll(verticalScrollState)
            .horizontalScroll(horizontalScrollState)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    var previousDistance = 0f

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            // Dua jari: pinch-zoom. Consume supaya scroll tidak
                            // ikut bereaksi terhadap gerakan dua jari ini.
                            val p1 = pressed[0].position
                            val p2 = pressed[1].position
                            val distance = kotlin.math.hypot(
                                (p1.x - p2.x).toDouble(),
                                (p1.y - p2.y).toDouble()
                            ).toFloat()

                            if (previousDistance > 0f) {
                                val zoomChange = distance / previousDistance
                                state.updateScale(state.scale * zoomChange)
                            }
                            previousDistance = distance
                            pressed.forEach { it.consume() }
                        } else {
                            // 1 jari (atau 0): reset, JANGAN consume — biarkan
                            // event diteruskan ke verticalScroll/horizontalScroll.
                            previousDistance = 0f
                        }

                        if (event.changes.none { it.pressed }) break
                    }
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

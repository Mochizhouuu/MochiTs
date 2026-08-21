package com.mochits.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
 * Dibungkus [BoxWithConstraints] agar tahu lebar viewport SEBENARNYA
 * (maxWidth) — dipakai untuk menghitung padding horizontal manual
 * ketika gambar (setelah di-scale) lebih SEMPIT dari layar, sehingga
 * gambar selalu tampak di tengah alih-alih nempel ke kiri.
 * Modifier.horizontalScroll tidak meng-center konten yang lebih kecil
 * dari viewport secara otomatis — makanya perlu dihitung manual di sini.
 */
@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val viewportWidthDp = maxWidth
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        val density = LocalDensity.current

        val displayWidthDp = with(density) { (state.intrinsicWidthPx * state.scale).toDp() }
        val displayHeightDp = with(density) { (state.intrinsicHeightPx * state.scale).toDp() }

        // Jika gambar lebih sempit dari viewport, beri padding kiri-kanan
        // yang sama agar tampak di tengah. Jika lebih lebar (bisa
        // discroll), padding 0 dan horizontalScroll yang bekerja.
        val horizontalPadding = if (displayWidthDp < viewportWidthDp) {
            (viewportWidthDp - displayWidthDp) / 2
        } else {
            0.dp
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
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
                                // Dua jari: pinch-zoom. Consume supaya scroll
                                // tidak ikut bereaksi terhadap gerakan ini.
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
                                // 1 jari: reset, JANGAN consume — biarkan
                                // diteruskan ke verticalScroll/horizontalScroll.
                                previousDistance = 0f
                            }

                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
                .padding(horizontal = horizontalPadding)
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

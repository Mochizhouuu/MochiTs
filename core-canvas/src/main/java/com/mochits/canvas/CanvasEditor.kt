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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Canvas untuk gambar long-strip (webtoon): gambar ditampilkan dalam
 * ukuran (intrinsicSize * scale) sesungguhnya, bisa di-scroll (1 jari)
 * dan di-zoom (2 jari, dengan titik pinch tetap "diam" di layar —
 * zoom-to-point) secara terpisah tanpa saling mengganggu.
 *
 * [onRequestAddTextAtViewportCenter] dipanggil oleh pemanggil (mis.
 * tombol tambah teks di :app) untuk menambah teks tepat di TENGAH
 * AREA YANG SEDANG TERLIHAT di layar saat ini — bukan tengah gambar
 * keseluruhan, yang pada gambar panjang seringkali di luar pandangan.
 */
@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    onReady: (addTextAtViewportCenter: (String) -> Unit) -> Unit = {},
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val viewportWidthDp = maxWidth
        val viewportHeightDp = maxHeight
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()

        val displayWidthDp = with(density) { (state.intrinsicWidthPx * state.scale).toDp() }
        val displayHeightDp = with(density) { (state.intrinsicHeightPx * state.scale).toDp() }

        val horizontalPadding = if (displayWidthDp < viewportWidthDp) {
            (viewportWidthDp - displayWidthDp) / 2
        } else {
            0.dp
        }

        // Expose fungsi tambah-teks-di-tengah-viewport ke pemanggil.
        onReady { text ->
            val scrollX = horizontalScrollState.value.toFloat()
            val scrollY = verticalScrollState.value.toFloat()
            val paddingPx = with(density) { horizontalPadding.toPx() }
            // Titik tengah viewport, dikonversi dari piksel LAYAR (termasuk
            // offset scroll & padding centering) ke piksel GAMBAR ASLI.
            val centerScreenX = scrollX + with(density) { viewportWidthDp.toPx() } / 2f - paddingPx
            val centerScreenY = scrollY + with(density) { viewportHeightDp.toPx() } / 2f
            state.addTextLayer(
                text,
                xInImagePx = centerScreenX / state.scale,
                yInImagePx = centerScreenY / state.scale
            )
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
                                val p1 = pressed[0].position
                                val p2 = pressed[1].position
                                val distance = kotlin.math.hypot(
                                    (p1.x - p2.x).toDouble(),
                                    (p1.y - p2.y).toDouble()
                                ).toFloat()
                                val focus = androidx.compose.ui.geometry.Offset(
                                    (p1.x + p2.x) / 2f,
                                    (p1.y + p2.y) / 2f
                                )

                                if (previousDistance > 0f) {
                                    val zoomChange = distance / previousDistance
                                    val oldScale = state.scale
                                    val newScale = (oldScale * zoomChange).coerceIn(0.5f, 4f)

                                    if (newScale != oldScale) {
                                        // ZOOM-TO-POINT: titik fokus pinch (dalam
                                        // koordinat konten, termasuk scroll saat
                                        // ini) harus tetap berada di posisi layar
                                        // yang sama setelah scale berubah. Maka
                                        // scroll offset disesuaikan sebanding
                                        // dengan rasio perubahan scale.
                                        val contentX = horizontalScrollState.value + focus.x
                                        val contentY = verticalScrollState.value + focus.y
                                        val ratio = newScale / oldScale

                                        state.updateScale(newScale)

                                        val newScrollX =
                                            (contentX * ratio - focus.x).roundToInt()
                                        val newScrollY =
                                            (contentY * ratio - focus.y).roundToInt()

                                        // Digabung dalam 1 coroutine berurutan (bukan
                                        // 2 launch terpisah) supaya scrollTo() X dan Y
                                        // dieksekusi pada frame yang sama, setelah
                                        // updateScale() di atas benar-benar diterapkan.
                                        coroutineScope.launch {
                                            horizontalScrollState.scrollTo(
                                                newScrollX.coerceAtLeast(0)
                                            )
                                            verticalScrollState.scrollTo(
                                                newScrollY.coerceAtLeast(0)
                                            )
                                        }
                                    }
                                }
                                previousDistance = distance
                                pressed.forEach { it.consume() }
                            } else {
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
        val fontSizePx = with(LocalDensity.current) {
            (layer.fontSizeSp * scaleState.value).sp.toPx()
        }
        // Ekstra padding agar stroke/shadow tidak terpotong di tepi Canvas.
        val extraPx = fontSizePx * 0.5f
        val (textWidthPx, textHeightPx) = com.mochits.text.measureStyledText(layer.text, fontSizePx)

        val widthDp = with(LocalDensity.current) { (textWidthPx + extraPx * 2).toDp() }
        val heightDp = with(LocalDensity.current) { (textHeightPx + extraPx * 2).toDp() }

        com.mochits.text.StyledText(
            text = layer.text,
            fontSizePx = fontSizePx,
            style = layer.style,
            modifier = Modifier
                .padding(with(LocalDensity.current) { extraPx.toDp() })
                .size(width = widthDp, height = heightDp)
        )
    }
}

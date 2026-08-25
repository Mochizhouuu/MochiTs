package com.mochits.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SELECTION_COLOR = Color(0xFF8B85FF)

@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    maskBitmap: android.graphics.Bitmap? = null,
    isMaskingActive: Boolean = false,
    maskToolMode: String = "PAN_ZOOM",
    onMaskStrokeComplete: ((List<Pair<Float, Float>>) -> Unit)? = null,
    onMaskTap: ((Float, Float) -> Unit)? = null,
    onReady: (addTextAtViewportCenter: (String) -> Unit) -> Unit = {},
    onEditLayerRequest: ((layerId: String) -> Unit)? = null,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val viewportWidthDp = maxWidth
        val viewportHeightDp = maxHeight
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()
        val density = LocalDensity.current
        val coroutineScope = rememberCoroutineScope()

        androidx.compose.runtime.LaunchedEffect(viewportWidthDp) {
            val viewportPx = with(density) { viewportWidthDp.toPx() }
            if (viewportPx > 0) {
                state.setInitialFitScale(viewportPx)
            }
        }

        val displayWidthDp = with(density) { (state.intrinsicWidthPx * state.scale).toDp() }
        val displayHeightDp = with(density) { (state.intrinsicHeightPx * state.scale).toDp() }

        val horizontalPadding = if (displayWidthDp < viewportWidthDp) {
            (viewportWidthDp - displayWidthDp) / 2
        } else {
            0.dp
        }

        val verticalPadding = if (displayHeightDp < viewportHeightDp) {
            (viewportHeightDp - displayHeightDp) / 2
        } else {
            0.dp
        }

        androidx.compose.runtime.LaunchedEffect(onReady) {
            onReady { text ->
                // Hitung ulang padding & scroll saat callback dipanggil agar
                // tidak memakai nilai stale dari komposisi sebelumnya.
                val viewportWidthPx = with(density) { viewportWidthDp.toPx() }
                val viewportHeightPx = with(density) { viewportHeightDp.toPx() }
                val displayWidthPx = state.intrinsicWidthPx * state.scale
                val displayHeightPx = state.intrinsicHeightPx * state.scale

                val padXPx = if (displayWidthPx < viewportWidthPx) {
                    (viewportWidthPx - displayWidthPx) / 2f
                } else 0f
                val padYPx = if (displayHeightPx < viewportHeightPx) {
                    (viewportHeightPx - displayHeightPx) / 2f
                } else 0f

                val centerScreenX = horizontalScrollState.value + viewportWidthPx / 2f - padXPx
                val centerScreenY = verticalScrollState.value + viewportHeightPx / 2f - padYPx
                state.addTextLayer(
                    text,
                    xInImagePx = centerScreenX / state.scale,
                    yInImagePx = centerScreenY / state.scale
                )
            }
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
                        var initialFocus = androidx.compose.ui.geometry.Offset.Unspecified

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.size >= 2) {
                                // Multi-touch zoom gesture
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

                                // Simpan fokus awal untuk referensi
                                if (initialFocus == androidx.compose.ui.geometry.Offset.Unspecified) {
                                    initialFocus = focus
                                }

                                if (previousDistance > 0f && previousDistance != distance) {
                                    val zoomChange = distance / previousDistance
                                    val oldScale = state.scale
                                    val newScale = (oldScale * zoomChange).coerceIn(0.1f, 5.0f)

                                    if (newScale != oldScale && kotlin.math.abs(zoomChange - 1f) > 0.01f) {
                                        val paddingXPx = with(density) { horizontalPadding.toPx() }
                                        val paddingYPx = with(density) { verticalPadding.toPx() }

                                        val contentX = horizontalScrollState.value + focus.x - paddingXPx
                                        val contentY = verticalScrollState.value + focus.y - paddingYPx
                                        val ratio = newScale / oldScale

                                        state.updateScale(newScale)

                                        val viewportWidthPx = with(density) { viewportWidthDp.toPx() }
                                        val viewportHeightPx = with(density) { viewportHeightDp.toPx() }
                                        val newDisplayWidthPx = state.intrinsicWidthPx * newScale
                                        val newDisplayHeightPx = state.intrinsicHeightPx * newScale

                                        val newPaddingXPx = if (newDisplayWidthPx < viewportWidthPx) {
                                            (viewportWidthPx - newDisplayWidthPx) / 2f
                                        } else 0f

                                        val newPaddingYPx = if (newDisplayHeightPx < viewportHeightPx) {
                                            (viewportHeightPx - newDisplayHeightPx) / 2f
                                        } else 0f

                                        val newScrollX =
                                            ((contentX * ratio) - focus.x + newPaddingXPx).roundToInt()
                                        val newScrollY =
                                            ((contentY * ratio) - focus.y + newPaddingYPx).roundToInt()

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
                                initialFocus = androidx.compose.ui.geometry.Offset.Unspecified
                            }

                            if (event.changes.none { it.pressed }) break
                        }
                    }
                }
                .padding(horizontal = horizontalPadding, vertical = verticalPadding)
        ) {
            Box(
                modifier = Modifier
                    .size(width = displayWidthDp, height = displayHeightDp)
                    .then(
                        if (isMaskingActive && maskToolMode != "PAN_ZOOM") {
                            Modifier.pointerInput(maskToolMode, state.scale) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                                    down.consume()
                                    val points = mutableListOf<Pair<Float, Float>>()

                                    // Posisi event sudah lokal terhadap Box gambar
                                    // (di dalam scroll & padding), jadi cukup dibagi
                                    // dengan scale untuk mendapat koordinat piksel gambar.
                                    val startImgX = (down.position.x / state.scale).coerceIn(0f, state.intrinsicWidthPx.toFloat())
                                    val startImgY = (down.position.y / state.scale).coerceIn(0f, state.intrinsicHeightPx.toFloat())
                                    points.add(startImgX to startImgY)

                                    var isMultiTouch = false
                                    while (true) {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }

                                        if (pressed.size >= 2) {
                                            isMultiTouch = true
                                            break
                                        }

                                        if (pressed.size == 1) {
                                            val change = pressed[0]
                                            change.consume()

                                            val imgX = (change.position.x / state.scale).coerceIn(0f, state.intrinsicWidthPx.toFloat())
                                            val imgY = (change.position.y / state.scale).coerceIn(0f, state.intrinsicHeightPx.toFloat())
                                            points.add(imgX to imgY)
                                        }

                                        if (event.changes.none { it.pressed }) break
                                    }

                                    if (!isMultiTouch && points.isNotEmpty()) {
                                        if (maskToolMode == "MAGIC_WAND" || maskToolMode == "COLOR_PIPETTE") {
                                            onMaskTap?.invoke(startImgX, startImgY)
                                        } else {
                                            onMaskStrokeComplete?.invoke(points)
                                        }
                                    }
                                }
                            }
                        } else Modifier
                    )
            ) {
                imageContent(
                    state.baseImagePath,
                    ContentScale.FillBounds,
                    Modifier.fillMaxSize()
                )

                if (maskBitmap != null) {
                    coil.compose.AsyncImage(
                        model = maskBitmap,
                        contentDescription = "Masker Overlay",
                        contentScale = ContentScale.FillBounds,
                        alpha = 0.5f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                state.textLayers.forEach { layer ->
                    DraggableTextLayer(
                        layer = layer,
                        scale = state.scale,
                        isSelected = state.selectedLayerId == layer.id,
                        onSelect = { state.selectLayer(layer.id) },
                        onDragStarted = { state.pushUndoSnapshot() },
                        onEditRequest = {
                            onEditLayerRequest?.invoke(layer.id)
                        },
                        onDragBy = { deltaXPx, deltaYPx ->
                            state.moveLayerBy(
                                layer.id,
                                deltaXPx / state.scale,
                                deltaYPx / state.scale
                            )
                        },
                        onResizeFontSize = { deltaSp ->
                            state.updateLayerFontSize(layer.id, layer.fontSizeSp + deltaSp)
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
    onDragStarted: () -> Unit,
    onEditRequest: () -> Unit,
    onDragBy: (deltaXPx: Float, deltaYPx: Float) -> Unit,
    onResizeFontSize: (deltaSp: Float) -> Unit
) {
    val screenX = layer.xInImagePx * scale
    val screenY = layer.yInImagePx * scale
    val scaleState = rememberUpdatedState(scale)

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .then(
                if (isSelected) {
                    Modifier
                        .border(
                            width = 1.5.dp,
                            color = SELECTION_COLOR,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .background(SELECTION_COLOR.copy(alpha = 0.08f))
                } else Modifier
            )
            .pointerInput(layer.id) {
                detectTapGestures(onDoubleTap = { onEditRequest() })
            }
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStarted()
                    }
                ) { change, dragAmount ->
                    change.consume()
                    onDragBy(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        val fontSizePx = with(LocalDensity.current) {
            (layer.fontSizeSp * scaleState.value).sp.toPx()
        }
        val extraPx = fontSizePx * 0.5f
        val (textWidthPx, textHeightPx) = com.mochits.text.measureStyledText(layer.text, fontSizePx, layer.style)

        val density = LocalDensity.current
        val widthDp = with(density) { (textWidthPx + extraPx * 2).toDp() }
        val heightDp = with(density) { (textHeightPx + extraPx * 2).toDp() }
        val extraDp = with(density) { extraPx.toDp() }

        com.mochits.text.StyledText(
            text = layer.text,
            fontSizePx = fontSizePx,
            style = layer.style,
            modifier = Modifier
                .padding(extraDp)
                .size(width = widthDp, height = heightDp)
        )

        // Corner Resize Handle when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 6.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(SELECTION_COLOR)
                    .border(1.dp, Color.White, CircleShape)
                    .pointerInput(layer.id + "_resize") {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val delta = (dragAmount.x + dragAmount.y) / 4f
                            onResizeFontSize(delta)
                        }
                    }
            )
        }
    }
}

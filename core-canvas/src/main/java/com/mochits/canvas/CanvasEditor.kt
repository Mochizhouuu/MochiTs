package com.mochits.canvas

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mochits.common.MaskToolMode
import com.mochits.text.StyledText
import com.mochits.text.measureStyledText
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val SELECTION_COLOR = Color(0xFF8B85FF)

@Composable
fun CanvasEditor(
    state: CanvasEditorState,
    modifier: Modifier = Modifier,
    maskBitmap: Bitmap? = null,
    isMaskingActive: Boolean = false,
    maskToolMode: MaskToolMode = MaskToolMode.PAN_ZOOM,
    onMaskStrokeComplete: ((List<Pair<Float, Float>>) -> Unit)? = null,
    onMaskTap: ((Float, Float) -> Unit)? = null,
    onReady: (addTextAtViewportCenter: (String) -> Unit) -> Unit = {},
    onEditLayerRequest: ((layerId: String) -> Unit)? = null,
    imageContent: @Composable (path: String, contentScale: ContentScale, modifier: Modifier) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }

        LaunchedEffect(viewportWidthPx, viewportHeightPx) {
            state.updateViewportSize(viewportWidthPx, viewportHeightPx)
        }

        LaunchedEffect(onReady) {
            onReady { text ->
                val vw = if (state.viewportWidthPx > 0f) state.viewportWidthPx else viewportWidthPx
                val vh = if (state.viewportHeightPx > 0f) state.viewportHeightPx else viewportHeightPx
                val centerViewportX = vw / 2f
                val centerViewportY = vh / 2f
                val imgX = (centerViewportX - state.offsetX) / state.scale
                val imgY = (centerViewportY - state.offsetY) / state.scale
                state.addTextLayer(text, imgX, imgY)
            }
        }

        val isMaskingToolActive = isMaskingActive && maskToolMode != MaskToolMode.PAN_ZOOM

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isMaskingToolActive, maskToolMode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(pass = PointerEventPass.Main)
                        var previousCentroid = down.position
                        var previousDistance = 0f

                        val strokePoints = mutableListOf<Pair<Float, Float>>()
                        var isSingleTouchMasking = isMaskingToolActive

                        if (isSingleTouchMasking) {
                            val imgX = ((down.position.x - state.offsetX) / state.scale).coerceIn(0f, state.intrinsicWidthPx.toFloat())
                            val imgY = ((down.position.y - state.offsetY) / state.scale).coerceIn(0f, state.intrinsicHeightPx.toFloat())
                            strokePoints.add(imgX to imgY)
                        }

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                // Multi-touch zoom & pan mode
                                isSingleTouchMasking = false
                                strokePoints.clear()

                                val p1 = pressed[0].position
                                val p2 = pressed[1].position
                                val currentCentroid = Offset((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
                                val currentDistance = hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble()).toFloat()

                                if (previousDistance > 0f) {
                                    val zoomFactor = currentDistance / previousDistance
                                    val panDx = currentCentroid.x - previousCentroid.x
                                    val panDy = currentCentroid.y - previousCentroid.y

                                    if (abs(zoomFactor - 1f) > 0.001f) {
                                        state.zoomAt(zoomFactor, currentCentroid.x, currentCentroid.y)
                                    }
                                    state.panBy(panDx, panDy)
                                }
                                previousCentroid = currentCentroid
                                previousDistance = currentDistance
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val change = pressed[0]
                                if (isSingleTouchMasking) {
                                    if (!change.isConsumed) {
                                        change.consume()
                                        val imgX = ((change.position.x - state.offsetX) / state.scale).coerceIn(0f, state.intrinsicWidthPx.toFloat())
                                        val imgY = ((change.position.y - state.offsetY) / state.scale).coerceIn(0f, state.intrinsicHeightPx.toFloat())
                                        strokePoints.add(imgX to imgY)
                                    }
                                } else {
                                    // 1-finger pan mode
                                    if (!change.isConsumed) {
                                        val panDx = change.position.x - previousCentroid.x
                                        val panDy = change.position.y - previousCentroid.y
                                        if (previousDistance == 0f) {
                                            state.panBy(panDx, panDy)
                                            change.consume()
                                        }
                                    }
                                    previousCentroid = change.position
                                }
                                previousDistance = 0f
                            }
                        }

                        if (isSingleTouchMasking && strokePoints.isNotEmpty()) {
                            val firstPt = strokePoints.first()
                            if (maskToolMode == MaskToolMode.MAGIC_WAND || maskToolMode == MaskToolMode.COLOR_PIPETTE) {
                                onMaskTap?.invoke(firstPt.first, firstPt.second)
                            } else {
                                onMaskStrokeComplete?.invoke(strokePoints)
                            }
                        }
                    }
                }
        ) {
            // Container transformed via matrix (offsetX, offsetY, scale)
            val imgWidthDp = with(density) { state.intrinsicWidthPx.toDp() }
            val imgHeightDp = with(density) { state.intrinsicHeightPx.toDp() }

            Box(
                modifier = Modifier
                    .size(width = imgWidthDp, height = imgHeightDp)
                    .graphicsLayer {
                        scaleX = state.scale
                        scaleY = state.scale
                        translationX = state.offsetX
                        translationY = state.offsetY
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                    }
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
                        onEditRequest = { onEditLayerRequest?.invoke(layer.id) },
                        onDragBy = { deltaXPx, deltaYPx ->
                            state.moveLayerBy(layer.id, deltaXPx, deltaYPx)
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
    val density = LocalDensity.current

    // fontSizePx disesuaikan untuk koordinat internal (graphicsLayer menagani scale visual)
    val fontSizePx = with(density) { layer.fontSizeSp.sp.toPx() }
    val extraPx = fontSizePx * 0.5f
    val (textWidthPx, textHeightPx) = remember(layer.text, fontSizePx, layer.style) {
        measureStyledText(layer.text, fontSizePx, layer.style)
    }

    val widthDp = with(density) { (textWidthPx + extraPx * 2).toDp() }
    val heightDp = with(density) { (textHeightPx + extraPx * 2).toDp() }
    val extraDp = with(density) { extraPx.toDp() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (layer.xInImagePx - extraPx).roundToInt(),
                    (layer.yInImagePx - extraPx).roundToInt()
                )
            }
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
                detectTapGestures(
                    onTap = { onSelect() },
                    onDoubleTap = { onEditRequest() }
                )
            }
            .pointerInput(layer.id) {
                detectDragGestures(
                    onDragStart = {
                        onSelect()
                        onDragStarted()
                    }
                ) { change, dragAmount ->
                    change.consume()
                    // dragAmount ada di dalam koordinat unscaled graphicsLayer
                    onDragBy(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        StyledText(
            text = layer.text,
            fontSizePx = fontSizePx,
            style = layer.style,
            modifier = Modifier
                .padding(extraDp)
                .size(width = widthDp, height = heightDp)
        )

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
                        detectDragGestures(
                            onDragStart = { onDragStarted() }
                        ) { change, dragAmount ->
                            change.consume()
                            val delta = (dragAmount.x + dragAmount.y) / 4f
                            onResizeFontSize(delta)
                        }
                    }
            )
        }
    }
}

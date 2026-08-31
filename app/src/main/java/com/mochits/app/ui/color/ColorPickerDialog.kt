package com.mochits.app.ui.color

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismissRequest: () -> Unit,
    onColorSelected: (Int) -> Unit
) {
    var hsv by remember(initialColor) { mutableStateOf(ColorUtils.colorToHsv(initialColor)) }
    var alpha by remember(initialColor) { mutableFloatStateOf(AndroidColor.alpha(initialColor) / 255f) }
    var hexInput by remember(initialColor) { mutableStateOf(ColorUtils.colorToHex(initialColor, includeAlpha = true)) }
    var isHexError by remember { mutableStateOf(false) }

    val currentColor = remember(hsv, alpha) {
        ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2], alpha)
    }

    LaunchedEffect(currentColor) {
        val newHex = ColorUtils.colorToHex(currentColor, includeAlpha = alpha < 1f || hexInput.length > 7)
        if (!isHexError && hexInput.uppercase() != newHex.uppercase()) {
            hexInput = newHex
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = "Pilih Warna Custom",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 1. Saturation-Value 2D Palette Box
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                ) {
                    val pureHueColor = remember(hsv[0]) {
                        Color(ColorUtils.hsvToColor(hsv[0], 1f, 1f, 1f))
                    }

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(hsv[0]) {
                                detectTapGestures { offset ->
                                    val sat = (offset.x / size.width).coerceIn(0f, 1f)
                                    val valVal = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                                    hsv = floatArrayOf(hsv[0], sat, valVal)
                                    isHexError = false
                                }
                            }
                            .pointerInput(hsv[0]) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val valVal = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                                    hsv = floatArrayOf(hsv[0], sat, valVal)
                                    isHexError = false
                                }
                            }
                    ) {
                        // White to Pure Hue Horizontal Gradient
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(Color.White, pureHueColor)
                            )
                        )
                        // Transparent to Black Vertical Gradient
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black)
                            )
                        )

                        // Selector Handle (Circle)
                        val selectorX = hsv[1] * size.width
                        val selectorY = (1f - hsv[2]) * size.height
                        drawCircle(
                            color = Color.White,
                            radius = 10.dp.toPx(),
                            center = Offset(selectorX, selectorY),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = Color.Black,
                            radius = 8.dp.toPx(),
                            center = Offset(selectorX, selectorY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // 2. Hue Slider Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Hue", style = MaterialTheme.typography.labelMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Red, Color.Yellow, Color.Green,
                                        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        val newHue = (offset.x / size.width).coerceIn(0f, 1f) * 360f
                                        hsv = floatArrayOf(newHue, hsv[1], hsv[2])
                                        isHexError = false
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, _ ->
                                        change.consume()
                                        val newHue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                                        hsv = floatArrayOf(newHue, hsv[1], hsv[2])
                                        isHexError = false
                                    }
                                }
                        ) {
                            val handleX = (hsv[0] / 360f) * size.width
                            drawCircle(
                                color = Color.White,
                                radius = 10.dp.toPx(),
                                center = Offset(handleX, size.height / 2f),
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = Color.Black,
                                radius = 8.dp.toPx(),
                                center = Offset(handleX, size.height / 2f),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }

                // 3. Alpha / Transparansi Slider Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Alpha / Transparansi: ${(alpha * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                    val colorOpaque = remember(hsv) { Color(ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2], 1f)) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, colorOpaque)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        alpha = (offset.x / size.width).coerceIn(0f, 1f)
                                        isHexError = false
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, _ ->
                                        change.consume()
                                        alpha = (change.position.x / size.width).coerceIn(0f, 1f)
                                        isHexError = false
                                    }
                                }
                        ) {
                            val handleX = alpha * size.width
                            drawCircle(
                                color = Color.White,
                                radius = 10.dp.toPx(),
                                center = Offset(handleX, size.height / 2f),
                                style = Stroke(width = 3.dp.toPx())
                            )
                            drawCircle(
                                color = Color.Black,
                                radius = 8.dp.toPx(),
                                center = Offset(handleX, size.height / 2f),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }

                // 4. Color Swatch Preview & Hex Input Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(currentColor), CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )

                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            hexInput = input
                            val parsed = ColorUtils.parseHexColor(input)
                            if (parsed != null) {
                                isHexError = false
                                hsv = ColorUtils.colorToHsv(parsed)
                                alpha = AndroidColor.alpha(parsed) / 255f
                            } else {
                                isHexError = true
                            }
                        },
                        label = { Text("Kode Hex (#RRGGBB / #AARRGGBB)") },
                        isError = isHexError,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onColorSelected(currentColor)
                    onDismissRequest()
                }
            ) {
                Text("Terapkan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Batal")
            }
        }
    )
}

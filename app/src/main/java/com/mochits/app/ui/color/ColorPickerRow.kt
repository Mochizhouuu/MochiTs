package com.mochits.app.ui.color

import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
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

/**
 * Baris preset warna + tombol eyedropper + panel custom inline.
 *
 * Panel custom MELUAS DI TEMPAT (bukan dialog floating) dan menerapkan
 * warna secara live. [onCustomColorChange] dipakai saat drag (tanpa snapshot
 * undo); [onColorSelected] untuk ketukan diskrit. [onPickStart]/[onPickEnd]
 * mengapit sesi pilih kustom (snapshot/autosave seperti slider).
 */
@Composable
fun ColorPickerRow(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    onEyedropperClick: () -> Unit,
    presetColors: List<Int> = ColorUtils.defaultPresetColors,
    modifier: Modifier = Modifier,
    onCustomColorChange: ((Int) -> Unit)? = null,
    onPickStart: () -> Unit = {},
    onPickEnd: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var wasExpanded by remember { mutableStateOf(false) }
    var hsv by remember { mutableStateOf(ColorUtils.colorToHsv(selectedColor)) }
    var alpha by remember { mutableFloatStateOf(AndroidColor.alpha(selectedColor) / 255f) }
    var hexInput by remember { mutableStateOf("") }
    var isHexError by remember { mutableStateOf(false) }

    // Sinkron dari warna terpilih HANYA saat panel dibuka; update live dari
    // parent tidak boleh me-reset drag yang sedang berjalan.
    LaunchedEffect(expanded) {
        if (expanded) {
            hsv = ColorUtils.colorToHsv(selectedColor)
            alpha = AndroidColor.alpha(selectedColor) / 255f
            hexInput = ColorUtils.colorToHex(selectedColor, includeAlpha = true)
            isHexError = false
            wasExpanded = true
            onPickStart()
        } else if (wasExpanded) {
            wasExpanded = false
            onPickEnd()
        }
    }

    val livePick = onCustomColorChange ?: onColorSelected
    fun emitLive() {
        isHexError = false
        livePick(ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2], alpha))
    }

    val currentColor = remember(hsv, alpha) {
        ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2], alpha)
    }
    LaunchedEffect(currentColor) {
        val newHex = ColorUtils.colorToHex(currentColor, includeAlpha = alpha < 1f || hexInput.length > 7)
        if (!isHexError && hexInput.uppercase() != newHex.uppercase()) {
            hexInput = newHex
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Eyedropper Button
            IconButton(
                onClick = onEyedropperClick,
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Colorize,
                    contentDescription = "Eyedropper Sample Warna",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 2. Custom Color Toggle (lingkaran berisi warna terpilih)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Color(selectedColor), CircleShape)
                    .border(
                        width = if (expanded) 2.5.dp else 1.dp,
                        color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = "Warna custom",
                    tint = if (ColorUtils.isLightColor(selectedColor)) Color.Black else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 3. Preset Colors Row (scrollable + target sentuh lega untuk jari)
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(presetColors) { c ->
                    val isSelected = !expanded && (selectedColor and 0x00FFFFFF) == (c and 0x00FFFFFF)
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(c), CircleShape)
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable { onColorSelected(c) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (ColorUtils.isLightColor(c)) Color.Black else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Saturation-Value 2D Palette Box (+ loupe saat drag:
                    // jari menutupi titik sentuh, jadi area sekitar pointer
                    // ditampilkan diperbesar + crosshair di titik pas).
                    var svPicking by remember { mutableStateOf(false) }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
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
                                    // Titik pilih di-offset ke atas agar tidak tertutup jari.
                                    val touchLift = 56.dp.toPx()
                                    detectTapGestures { offset ->
                                        val sat = (offset.x / size.width).coerceIn(0f, 1f)
                                        val valVal = (1f - ((offset.y - touchLift) / size.height)).coerceIn(0f, 1f)
                                        hsv = floatArrayOf(hsv[0], sat, valVal)
                                        emitLive()
                                    }
                                }
                                .pointerInput(hsv[0]) {
                                    val touchLift = 56.dp.toPx()
                                    detectDragGestures(
                                        onDragStart = { svPicking = true },
                                        onDragEnd = { svPicking = false },
                                        onDragCancel = { svPicking = false }
                                    ) { change, _ ->
                                        change.consume()
                                        val sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                        val valVal = (1f - ((change.position.y - touchLift) / size.height)).coerceIn(0f, 1f)
                                        hsv = floatArrayOf(hsv[0], sat, valVal)
                                        emitLive()
                                    }
                                }
                        ) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.White, pureHueColor)
                                )
                            )
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black)
                                )
                            )

                            val selectorX = hsv[1] * size.width
                            val selectorY = (1f - hsv[2]) * size.height
                            // Crosshair penuh: titik pas tetap terlihat walau tertutup jari.
                            drawLine(
                                color = Color.White.copy(alpha = 0.55f),
                                start = Offset(0f, selectorY),
                                end = Offset(size.width, selectorY),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.55f),
                                start = Offset(selectorX, 0f),
                                end = Offset(selectorX, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
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

                        // Loupe 4x melayang di atas, mengikuti sumbu X pointer.
                        if (svPicking) {
                            val loupeSize = 96.dp
                            val loupeX = ((hsv[1] * maxWidth) - loupeSize / 2)
                                .coerceIn(0.dp, maxWidth - loupeSize)
                            SvLoupe(
                                pickSat = hsv[1],
                                pickVal = hsv[2],
                                hue = hsv[0],
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = loupeX, y = 8.dp)
                            )
                        }
                    }

                    // 2. Hue Slider Bar
                    Text(
                        text = "Sentuh sedikit di bawah target — titik pilih berada di atas jarimu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SliderBar(
                        label = "Hue: ${hsv[0].toInt()}°",
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Red, Color.Yellow, Color.Green,
                                Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                            )
                        ),
                        handleFraction = hsv[0] / 360f,
                        onFractionChange = {
                            hsv = floatArrayOf(it * 360f, hsv[1], hsv[2])
                            emitLive()
                        }
                    )

                    // 3. Alpha / Transparansi Slider Bar
                    val colorOpaque = remember(hsv) { Color(ColorUtils.hsvToColor(hsv[0], hsv[1], hsv[2], 1f)) }
                    SliderBar(
                        label = "Alpha / Transparansi: ${(alpha * 100).toInt()}%",
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, colorOpaque)
                        ),
                        handleFraction = alpha,
                        onFractionChange = {
                            alpha = it
                            emitLive()
                        }
                    )

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
                                    emitLive()
                                } else {
                                    isHexError = true
                                }
                            },
                            label = { Text("Hex (#RRGGBB / #AARRGGBB)") },
                            isError = isHexError,
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 5. Selesai (tutup panel)
                    TextButton(onClick = { expanded = false }) {
                        Text("Selesai")
                    }
                }
            }
        }
    }
}

@Composable
private fun SvLoupe(
    pickSat: Float,
    pickVal: Float,
    hue: Float,
    modifier: Modifier = Modifier
) {
    // Kaca pembesar prosedural: sampling fungsi gradien yang sama dengan
    // kotak SV (putih→hue lalu menuju hitam), tanpa butuh ukuran kotak.
    val pure = remember(hue) { Color(ColorUtils.hsvToColor(hue, 1f, 1f, 1f)) }
    Canvas(
        modifier = modifier
            .size(96.dp)
            .background(Color.Black, CircleShape)
            .border(2.dp, Color.White, CircleShape)
    ) {
        val cells = 12
        val zoom = 4f
        val cw = size.width / cells
        val ch = size.height / cells
        for (j in 0 until cells) {
            for (i in 0 until cells) {
                val s = (pickSat + ((i + 0.5f) / cells - 0.5f) / zoom).coerceIn(0f, 1f)
                val v = (pickVal + (0.5f - (j + 0.5f) / cells) / zoom).coerceIn(0f, 1f)
                val c = Color.White.lerp(pure, s) * v
                drawRect(
                    color = c,
                    topLeft = Offset(i * cw, j * ch),
                    size = androidx.compose.ui.geometry.Size(cw + 1f, ch + 1f)
                )
            }
        }
        // Crosshair tengah loupe = titik yang sedang dipilih.
        drawLine(
            color = Color.White,
            start = Offset(size.width / 2f, size.height / 2f - 8.dp.toPx()),
            end = Offset(size.width / 2f, size.height / 2f + 8.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
        drawLine(
            color = Color.White,
            start = Offset(size.width / 2f - 8.dp.toPx(), size.height / 2f),
            end = Offset(size.width / 2f + 8.dp.toPx(), size.height / 2f),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
private fun SliderBar(
    label: String,
    brush: Brush,
    handleFraction: Float,
    onFractionChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(brush = brush, shape = RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onFractionChange((offset.x / size.width).coerceIn(0f, 1f))
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            onFractionChange((change.position.x / size.width).coerceIn(0f, 1f))
                        }
                    }
            ) {
                val handleX = handleFraction.coerceIn(0f, 1f) * size.width
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
}

package com.mochits.app.ui.color

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ColorPickerRow(
    selectedColor: Int,
    onColorSelected: (Int) -> Unit,
    onEyedropperClick: () -> Unit,
    presetColors: List<Int> = listOf(
        AndroidColor.BLACK,
        AndroidColor.WHITE,
        AndroidColor.RED,
        AndroidColor.BLUE,
        AndroidColor.GREEN,
        AndroidColor.YELLOW,
        AndroidColor.MAGENTA,
        AndroidColor.CYAN
    ),
    modifier: Modifier = Modifier
) {
    var showPickerDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
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

        // 2. Color Palette / Wheel Dialog Button
        IconButton(
            onClick = { showPickerDialog = true },
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.Palette,
                contentDescription = "Color Picker Dialog",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }

        // 3. Preset Colors Row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(presetColors) { c ->
                val isSelected = (selectedColor and 0x00FFFFFF) == (c and 0x00FFFFFF)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(c), CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        val isLight = (AndroidColor.red(c) * 0.299 + AndroidColor.green(c) * 0.587 + AndroidColor.blue(c) * 0.114) > 180
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = if (isLight) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showPickerDialog) {
        ColorPickerDialog(
            initialColor = selectedColor,
            onDismissRequest = { showPickerDialog = false },
            onColorSelected = { color ->
                onColorSelected(color)
                showPickerDialog = false
            }
        )
    }
}

package com.gbapal.companion.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Draws a piece of pixel art written as rows of '1' (filled) and '0' (empty),
 * scaled to fill whatever size [modifier] gives it.
 *
 * Writing the art as strings keeps each icon readable as a picture in the
 * source -- you can see the heart in the literal -- and means an icon is
 * data rather than drawing code, so adding one costs a few lines of text
 * instead of another copy of this loop.
 */
@Composable
fun PixelIcon(rows: List<String>, modifier: Modifier, color: Color = MonoAccent) {
    Canvas(modifier = modifier) {
        val pixelWidth = size.width / rows[0].length
        val pixelHeight = size.height / rows.size
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, cell ->
                if (cell == '1') {
                    drawRect(
                        color = color,
                        topLeft = Offset(col * pixelWidth, row * pixelHeight),
                        size = Size(pixelWidth, pixelHeight),
                    )
                }
            }
        }
    }
}

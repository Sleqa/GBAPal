package com.gbapal.companion.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelIcon

// Heal is the one exception to Mono's otherwise strict black/white palette
// (see MonoTheme's doc comment) -- proper colour is what lets HealHeart's
// colour double as its own enabled/disabled signal.
private val HealRed = Color(0xFFE24C4C)

// 9x5 battery outline: a hollow body with a single-pixel cap at the right.
// The interior (cols 1-6, rows 1-3) is what the charge bars fill in.
private val BATTERY_PIXEL_ROWS = listOf(
    "011111100",
    "100000010",
    "100000011",
    "100000010",
    "011111100",
)
private val BATTERY_INTERIOR_COLS = 1..6
private val BATTERY_INTERIOR_ROWS = 1..3

// 7x6 pixel-art heart, NES-style: 1 = filled pixel, 0 = empty.
private val HEART_PIXEL_ROWS = listOf(
    "0110110",
    "1111111",
    "1111111",
    "0111110",
    "0011100",
    "0001000",
)

// 8x8 wrench, same blocky style as the heart: open jaw top-left, diagonal
// shaft down to the bottom-right.
private val WRENCH_PIXEL_ROWS = listOf(
    "00011000",
    "00111100",
    "01100110",
    "00110000",
    "00011000",
    "00110000",
    "01100000",
    "11000000",
)

/**
 * The battery outline with its interior filled proportionally to [percent].
 *
 * Filling the art itself, rather than drawing bars over a drawn outline,
 * keeps the battery a plain [PixelIcon] like every other glyph -- the charge
 * level is just a different picture.
 */
private fun batteryRows(percent: Int): List<String> {
    val filledCols = (BATTERY_INTERIOR_COLS.count() * (percent.coerceIn(0, 100) / 100f)).toInt()
    val lastFilled = BATTERY_INTERIOR_COLS.first + filledCols - 1
    return BATTERY_PIXEL_ROWS.mapIndexed { row, line ->
        if (row !in BATTERY_INTERIOR_ROWS) {
            line
        } else {
            line.mapIndexed { col, cell ->
                if (col in BATTERY_INTERIOR_COLS.first..lastFilled) '1' else cell
            }.joinToString("")
        }
    }
}

/** Pixel battery glyph plus its percentage, top-right of the hub. */
@Composable
internal fun BatteryIcon(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelIcon(batteryRows(percent), Modifier.size(width = 26.dp, height = 14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        MonoLabel("$percent%", color = MonoText, fontSize = 14.sp)
    }
}

/** Pixel heart heal button. Greyscale and inert while [enabled] is false (in battle). */
@Composable
internal fun HealHeart(enabled: Boolean, onClick: () -> Unit) {
    PixelIcon(
        rows = HEART_PIXEL_ROWS,
        color = if (enabled) HealRed else MonoTextMuted,
        modifier = Modifier
            .size(width = 28.dp, height = 24.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/** Small on/off label for the infinite-repel toggle: accent when active, muted when off. */
@Composable
internal fun RepelToggle(enabled: Boolean, onToggle: () -> Unit) {
    MonoLabel(
        text = if (enabled) "REPEL ●" else "REPEL ○",
        color = if (enabled) MonoAccent else MonoTextMuted,
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(6.dp),
    )
}

/** Pixel wrench settings icon, top-right of the hub. Always enabled. */
@Composable
internal fun SettingsWrench(onClick: () -> Unit) {
    PixelIcon(
        rows = WRENCH_PIXEL_ROWS,
        modifier = Modifier
            .size(22.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

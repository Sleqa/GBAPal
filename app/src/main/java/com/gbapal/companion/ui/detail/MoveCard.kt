package com.gbapal.companion.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelIcon

/**
 * One cell of the 2x2 move grid: name (type-tinted) with a category icon
 * beside it, then type/PP, then power/accuracy. No border. [power] 0 and
 * [accuracy] 0 are both real in-data conventions for "doesn't apply" -- a
 * Status move with no power, and a move that can never miss -- so both
 * render as "--" rather than a misleading "0".
 */
@Composable
internal fun MoveCard(
    name: String,
    type: String,
    category: String,
    power: Int,
    accuracy: Int,
    pp: Int,
    ppMax: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = typeColor(type)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(name.uppercase(), color = color, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(modifier = Modifier.width(5.dp))
            MoveCategoryIcon(category)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoLabel(type.uppercase(), color = MonoTextMuted, fontSize = 10.sp)
            MonoLabel("PP $pp/$ppMax", color = MonoTextMuted, fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoLabel(if (power > 0) "POW $power" else "POW --", color = MonoTextMuted, fontSize = 10.sp)
            MonoLabel(if (accuracy > 0) "ACC $accuracy%" else "ACC --", color = MonoTextMuted, fontSize = 10.sp)
        }
    }
}

private val PhysicalColor = Color(0xFFE0793C)
private val SpecialColor = Color(0xFF9878E8)

// 7x7 pixel-art category glyphs, same blocky style as the rest of the app's
// icons: a filled diamond (impact) for Physical, a 4-point spark for
// Special, and a hollow ring (a generic "effect" glyph, since Status covers
// both buffs and debuffs) for Status.
private val CATEGORY_ICON_PHYSICAL = listOf(
    "0001000",
    "0011100",
    "0111110",
    "1111111",
    "0111110",
    "0011100",
    "0001000",
)
private val CATEGORY_ICON_SPECIAL = listOf(
    "0001000",
    "0001000",
    "0101010",
    "1111111",
    "0101010",
    "0001000",
    "0001000",
)
private val CATEGORY_ICON_STATUS = listOf(
    "0011100",
    "0100010",
    "1000001",
    "1000001",
    "1000001",
    "0100010",
    "0011100",
)

/** Small pixel glyph for a move's category (Physical/Special/Status), coloured per category. */
@Composable
private fun MoveCategoryIcon(category: String) {
    val (rows, color) = when (category) {
        "Physical" -> CATEGORY_ICON_PHYSICAL to PhysicalColor
        "Special" -> CATEGORY_ICON_SPECIAL to SpecialColor
        else -> CATEGORY_ICON_STATUS to MonoTextMuted
    }
    PixelIcon(rows, Modifier.size(11.dp), color)
}

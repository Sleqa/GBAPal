package com.gbapal.companion.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gbapal.companion.R

/**
 * Mono: pure OLED black everywhere, white text and chrome, and the
 * Upheaval display font throughout. The only colour allowed anywhere in
 * this theme is Pokemon type colour (see PokemonDetailScreen's
 * TypeBadge/MoveCard) -- everything else is black, white, or an alpha
 * step of white.
 */
val MonoBg = Color(0xFF000000)
val MonoText = Color(0xFFFFFFFF)
val MonoTextMuted = Color(0x99FFFFFF) // white at ~60% alpha
val MonoAccent = Color(0xFFFFFFFF)
val MonoAccentGlow = Color(0x40FFFFFF) // white at ~25% alpha, for shadow/glow tints only

val MonoFont = FontFamily(Font(R.font.upheaval, FontWeight.Normal))

@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MonoText,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = TextStyle(
            fontFamily = MonoFont,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = fontSize * 1.3f,
        ),
    )
}

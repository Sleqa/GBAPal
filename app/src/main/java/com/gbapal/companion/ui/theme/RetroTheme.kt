package com.gbapal.companion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import com.gbapal.companion.R

/**
 * GBA Pokemon-flavored theme: pixel font, bordered menu panels, classic HP bars.
 * A styled approximation -- it does NOT reuse Unbound's own tile/font art (that
 * would require extracting graphics from the ROM), but reads as retro/in-genre.
 */
object RetroTheme {
    val pixelFont = FontFamily(Font(R.font.press_start_2p, FontWeight.Normal))

    // Palette (FireRed/LeafGreen menu feel).
    val background = Color(0xFF18244A)   // deep navy app background
    val panel = Color(0xFFF7F7E8)        // cream menu panel
    val panelBorder = Color(0xFF20304A)  // dark navy border
    val panelBorderInner = Color(0xFF98A8C8)
    val text = Color(0xFF20304A)         // dark navy text
    val textOnDark = Color(0xFFF7F7E8)
    val accent = Color(0xFFD83030)       // pokeball red
}

@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RetroTheme.text,
    fontSize: androidx.compose.ui.unit.TextUnit = 10.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = TextStyle(fontFamily = RetroTheme.pixelFont, fontSize = fontSize, lineHeight = fontSize * 1.4f),
    )
}

/** A cream menu panel with the classic double-line dark border and sharp corners. */
@Composable
fun RetroPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .background(RetroTheme.panelBorder, RectangleShape)
            .padding(2.dp)
            .background(RetroTheme.panel, RectangleShape)
            .border(1.dp, RetroTheme.panelBorderInner, RectangleShape)
            .padding(6.dp),
    ) {
        content()
    }
}

/** Classic segmented-look HP bar: white track outline, white fill sized by fraction. */
@Composable
fun PixelHpBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.35f), RectangleShape)
            .padding(1.dp)
            .background(Color.Black, RectangleShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(6.dp)
                .background(Color.White, RectangleShape),
        )
    }
}

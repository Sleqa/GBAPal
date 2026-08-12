package com.gbapal.companion.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted

/**
 * Full-screen settings overlay: the QOL Mods toggle (heal heart + repel
 * toggle on the hub) and the opponent stat-comparison toggle. More settings
 * can grow here later.
 */
@Composable
fun SettingsScreen(
    qolModsEnabled: Boolean,
    onQolModsChange: (Boolean) -> Unit,
    statCompareEnabled: Boolean,
    onStatCompareChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            // Consumes every tap across the full screen -- same reasoning as
            // OpponentScreen/PokemonDetailScreen: a background-only Box doesn't
            // participate in hit testing, so without this a tap on blank space
            // here would fall through to the hub underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MonoLabel(text = "SETTINGS", color = MonoText, fontSize = 20.sp)
                MonoLabel(
                    text = "CLOSE",
                    color = MonoAccent,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        )
                        .padding(8.dp),
                )
            }

            SettingToggle(
                title = "QOL MODS",
                subtitle = "Heal button + infinite repel toggle on the hub",
                enabled = qolModsEnabled,
                onToggle = { onQolModsChange(!qolModsEnabled) },
                modifier = Modifier.padding(top = 24.dp),
            )

            SettingToggle(
                title = "STAT COMPARE",
                subtitle = "Colour an opponent's stats against your active Pokemon",
                enabled = statCompareEnabled,
                onToggle = { onStatCompareChange(!statCompareEnabled) },
            )

        }
    }
}

/** One settings row: name, one-line explanation, and an ON/OFF state on the right. */
@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            MonoLabel(text = title, color = MonoText, fontSize = 16.sp)
            MonoLabel(text = subtitle, color = MonoTextMuted, fontSize = 11.sp)
        }
        MonoLabel(
            text = if (enabled) "ON" else "OFF",
            color = if (enabled) MonoAccent else MonoTextMuted,
            fontSize = 15.sp,
        )
    }
}

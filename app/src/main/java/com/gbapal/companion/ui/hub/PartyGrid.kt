package com.gbapal.companion.ui.hub

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.ui.detail.StatusBadge
import com.gbapal.companion.ui.theme.FaintedSpriteFilter
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted

/*
 * The band the party grid occupies, shared by the hub and the opponent screen
 * so the six Pokemon land in identical screen positions on both.
 *
 * Fixed heights rather than each screen's own intrinsic ones, because the two
 * headers hold different things -- a battery and a wrench on the hub, a CLOSE
 * label and the save-team controls on the opponent screen -- and letting them
 * size themselves is what made the grids disagree. Anything taller than the bar
 * (the save/discard stack) overflows into the gap the grid leaves above its
 * first row rather than pushing the grid down.
 */

/** Fits the opponent screen's CLOSE label, the tallest thing either header holds. */
internal val PARTY_GRID_TOP_BAR = 40.dp

/** Fits the hub's OPPONENT button and the opponent screen's ACTIVE BATTLE one. */
internal val PARTY_GRID_BOTTOM_BAR = 46.dp

/**
 * Up to six Pokemon laid out as three centered rows of two: the middle row
 * sits at the vertical center of [modifier]'s available height, and the top
 * and bottom rows space out symmetrically to fill the rest of it. Within a
 * row, the pair is compressed toward the horizontal center rather than
 * spread across the full width.
 */
@Composable
internal fun PartyGrid(
    mons: List<HubMon>,
    client: RetroArchClient,
    map: MemoryMap,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        mons.chunked(2).forEachIndexed { rowIndex, rowMons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            ) {
                rowMons.forEachIndexed { colIndex, mon ->
                    MonEntry(mon, client, map) { onSelect(rowIndex * 2 + colIndex) }
                }
            }
        }
    }
}

@Composable
internal fun MonEntry(mon: HubMon, client: RetroArchClient, map: MemoryMap, onClick: () -> Unit) {
    var sprite by remember(mon.speciesId, map) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mon.speciesId, map) {
        sprite = SpriteAssets.romFrontSprite(client, map, mon.speciesId)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        val currentSprite = sprite
        if (currentSprite != null) {
            Image(
                bitmap = currentSprite,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                colorFilter = if (mon.currentHp == 0) FaintedSpriteFilter else null,
                modifier = Modifier.size(68.dp),
            )
        } else {
            Box(modifier = Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                MonoLabel("?", color = MonoTextMuted, fontSize = 20.sp)
            }
        }
        MonoLabel(mon.nickname.uppercase(), color = MonoText, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Lv${mon.level}", color = MonoTextMuted, fontSize = 13.sp)
            mon.status?.let {
                Spacer(modifier = Modifier.width(6.dp))
                StatusBadge(it)
            }
        }
    }
}

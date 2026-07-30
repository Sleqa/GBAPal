package com.gbapal.companion.ui.dexnav

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.gbapal.companion.pokemon.DexNavData
import com.gbapal.companion.pokemon.DexNavScan
import com.gbapal.companion.pokemon.NameTables
import com.gbapal.companion.pokemon.RomSpeciesData
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.pokemon.WildEncounterData
import com.gbapal.companion.pokemon.WildEncounterSlot
import com.gbapal.companion.pokemon.WildEncounters
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val DEXNAV_POLL_INTERVAL_MS = 2_000L
private const val MAP_POLL_INTERVAL_MS = 1_000L
private const val GRID_COLUMNS = 6

/**
 * Live DexNav readout: the current route's Water/Land encounters as a sprite
 * grid (like CFRU's own in-game DexNav GUI), and below that the currently
 * targeted scan's details and odds -- CFRU computes these live in-game but
 * doesn't display them; see DexNavData for the source of the probability
 * tables. [inBattle] must reflect the caller's own battle-state tracking
 * (see HubScreen) since CFRU reuses the same pointer for battle state
 * mid-battle.
 */
@Composable
fun DexNavScreen(client: RetroArchClient, names: NameTables, map: MemoryMap, inBattle: Boolean, onClose: () -> Unit) {
    var scan by remember { mutableStateOf<DexNavScan?>(null) }
    var chain by remember { mutableIntStateOf(0) }
    var speciesName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inBattle) {
        while (isActive) {
            scan = DexNavData.activeScan(client, inBattle)
            chain = DexNavData.chain(client) ?: chain
            speciesName = scan?.let { RomSpeciesData.speciesName(client, it.speciesId) }
            delay(DEXNAV_POLL_INTERVAL_MS)
        }
    }

    // Route encounters only need re-resolving when the map actually changes
    // -- the header scan is the expensive part, so this avoids redoing it
    // every poll tick while just standing still.
    var mapKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var encounters by remember { mutableStateOf<WildEncounters?>(null) }
    var encounterSprites by remember { mutableStateOf<Map<Int, ImageBitmap?>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val currentMapKey = WildEncounterData.currentMapKey(client)
            if (currentMapKey != null && currentMapKey != mapKey) {
                mapKey = currentMapKey
                val currentEncounters = WildEncounterData.encountersFor(client, currentMapKey)
                encounters = currentEncounters
                val speciesIds = currentEncounters
                    ?.let { it.land + it.water }
                    ?.map { it.speciesId }
                    ?.distinct()
                    ?: emptyList()
                encounterSprites = speciesIds.associateWith { id -> SpriteAssets.romFrontSprite(client, map, id) }
            }
            delay(MAP_POLL_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MonoLabel(text = "DEXNAV", color = MonoText, fontSize = 20.sp)
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

            Spacer(modifier = Modifier.height(12.dp))
            MonoLabel(text = "CHAIN: $chain", color = MonoText, fontSize = 16.sp)

            Spacer(modifier = Modifier.height(20.dp))

            val currentEncounters = encounters
            if (currentEncounters == null) {
                MonoLabel(
                    text = "No wild encounter data found for this map.",
                    color = MonoTextMuted,
                    fontSize = 13.sp,
                )
            } else {
                EncounterSpriteGrid("Water", currentEncounters.water, encounterSprites)
                Spacer(modifier = Modifier.height(16.dp))
                EncounterSpriteGrid("Land", currentEncounters.land, encounterSprites)
            }

            Spacer(modifier = Modifier.height(24.dp))

            val currentScan = scan
            if (currentScan == null) {
                MonoLabel(
                    text = "No active scan. Point your DexNav at wild grass in-game to see live odds here.",
                    color = MonoTextMuted,
                    fontSize = 13.sp,
                )
            } else {
                MonoLabel(
                    text = "${speciesName ?: "Species #${currentScan.speciesId}"} -- Lv ${currentScan.pokemonLevel}",
                    color = MonoText,
                    fontSize = 18.sp,
                )
                MonoLabel(
                    text = "Search level: ${currentScan.searchLevel}",
                    color = MonoTextMuted,
                    fontSize = 13.sp,
                )
                if (currentScan.heldItemId != 0) {
                    MonoLabel(
                        text = "Held item: ${names.itemName(currentScan.heldItemId)}",
                        color = MonoTextMuted,
                        fontSize = 13.sp,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                MonoLabel(text = "ODDS THIS ENCOUNTER", color = MonoTextMuted, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))

                val hiddenAbilityPct = DexNavData.hiddenAbilityPercent(currentScan.searchLevel)
                val eggMovePct = DexNavData.eggMovePercent(currentScan.searchLevel)
                val (oneStar, twoStar, threeStar) = DexNavData.ivPotentialPercents(currentScan.searchLevel)
                val shinyProbability = DexNavData.shinyProbability(currentScan.searchLevel, chain)

                OddsRow("Hidden ability", "$hiddenAbilityPct%")
                OddsRow("Egg move", "$eggMovePct%")
                OddsRow("1 perfect IV", "$oneStar%")
                OddsRow("2 perfect IVs", "$twoStar%")
                OddsRow("3 perfect IVs", "$threeStar%")
                OddsRow("Shiny", shinyOddsText(shinyProbability))

                Spacer(modifier = Modifier.height(14.dp))
                MonoLabel(
                    text = "Shiny odds don't include a separate random +4 bonus roll " +
                        "CFRU applies about 4% of the time, so the real odds are " +
                        "slightly better than shown.",
                    color = MonoTextMuted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Sprite-grid section like CFRU's own DexNav GUI: icon + level range, no names. */
@Composable
private fun EncounterSpriteGrid(label: String, slots: List<WildEncounterSlot>, sprites: Map<Int, ImageBitmap?>) {
    if (slots.isEmpty()) return
    MonoLabel(text = label.uppercase(), color = MonoAccent, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(6.dp))
    slots.chunked(GRID_COLUMNS).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth()) {
            row.forEach { slot ->
                EncounterSpriteCell(slot, sprites[slot.speciesId], modifier = Modifier.weight(1f))
            }
            repeat(GRID_COLUMNS - row.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EncounterSpriteCell(slot: WildEncounterSlot, sprite: ImageBitmap?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (sprite != null) {
            Image(
                bitmap = sprite,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                MonoLabel("?", color = MonoTextMuted, fontSize = 16.sp)
            }
        }
        val levelText = if (slot.minLevel == slot.maxLevel) "${slot.minLevel}" else "${slot.minLevel}-${slot.maxLevel}"
        MonoLabel(levelText, color = MonoTextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun OddsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MonoLabel(text = label, color = MonoTextMuted, fontSize = 14.sp)
        MonoLabel(text = value, color = MonoText, fontSize = 14.sp)
    }
}

private fun shinyOddsText(probability: Double): String {
    if (probability <= 0.0) return "0%"
    val oneInN = (1.0 / probability).let { if (it < 10) "%.1f".format(it) else it.toInt().toString() }
    return "1 in $oneInN"
}

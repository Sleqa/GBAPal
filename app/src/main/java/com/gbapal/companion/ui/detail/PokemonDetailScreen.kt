package com.gbapal.companion.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.network.DexKind
import com.gbapal.companion.network.DexResult
import com.gbapal.companion.network.PokeApiClient
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.pokemon.GameData
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.ui.hub.HubMon
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelHpBar
import com.gbapal.companion.ui.theme.PixelIcon

/** Full-screen Mono-styled summary for one party Pokemon: stats, moveset+PP, item, ability. No frames or borders -- sections are separated by whitespace and headers alone. */
@Composable
fun PokemonDetailScreen(
    mon: HubMon,
    gameData: GameData,
    client: RetroArchClient,
    map: MemoryMap,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /**
     * The Pokemon on the *other* side of the battle, when one is known and the
     * stat-compare setting is on. Each of [mon]'s stats is then tinted by how
     * it measures up: red where [mon] beats it, green where it loses. Null
     * leaves every stat the usual flat white, which is what the player's own
     * party screen passes -- comparing a Pokemon against itself says nothing.
     */
    compareAgainst: HubMon? = null,
    /**
     * Live battle stat stages for [mon] -- ATK, DEF, SPD, SP.ATK, SP.DEF, in
     * that order, each -6..+6 relative to neutral -- when [mon] is the one
     * actually out on the field mid-battle. Null (the default, and what the
     * party screen passes for a benched Pokemon) shows every stat plain with
     * no stage badge.
     */
    statStages: List<Int>? = null,
) {
    var sprite by remember(mon.speciesId, map) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mon.speciesId, map) {
        sprite = SpriteAssets.romFrontSprite(client, map, mon.speciesId)
    }

    // Tap-to-describe for the ability, held item, and each move. The lookup is
    // by name rather than by this ROM's internal id, which is what lets one
    // source answer for every profile -- see PokeApiClient.
    val context = LocalContext.current
    val pokeApi = remember { PokeApiClient(context.cacheDir) }
    var dexQuery by remember { mutableStateOf<DexQuery?>(null) }
    var dexResult by remember { mutableStateOf<DexResult?>(null) }
    // Bumped by RETRY to re-run the effect for a query already showing an error.
    var dexAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(dexQuery, dexAttempt) {
        val query = dexQuery
        if (query == null) {
            dexResult = null
            return@LaunchedEffect
        }
        dexResult = null
        dexResult = pokeApi.description(query.kind, query.title)
    }

    // GameData already holds this species: the party poll prefetches every
    // member's data before building the HubMon list, so these are cache hits
    // rather than fresh reads, and no async state is needed here.
    val speciesName = gameData.speciesName(mon.speciesId)
    val displayName = mon.nickname.ifBlank { speciesName }
    val baseEntry = gameData.entry(mon.speciesId)
    val types = listOfNotNull(baseEntry?.type1, baseEntry?.type2)
    val (weaknesses, resists) = remember(baseEntry?.type1, baseEntry?.type2) {
        weaknessesAndResists(baseEntry?.type1, baseEntry?.type2)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            // Consumes every tap across the full screen -- without this, a
            // background-only Box doesn't participate in hit testing, so a
            // tap over blank space here falls through to HubScreen underneath
            // and can silently trigger its heal/repel buttons mid-detail-view.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        // Edge tap zones for prev/next navigation, 30% of the screen width
        // each side, full height. Declared before the scrollable content
        // below so real interactive elements (the CLOSE button, etc.) still
        // take priority wherever they overlap -- a plain Box like these
        // doesn't consume a tap that landed on something in front of it, so
        // these only ever fire in genuinely empty margin space. No visual
        // indicator by design.
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPrevious,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onNext,
                ),
        )

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Looks up by speciesName even when a nickname is showing --
                    // a nickname isn't a real species and would just 404.
                    MonoLabel(
                        displayName.uppercase(),
                        color = MonoText,
                        fontSize = 20.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { dexQuery = DexQuery(DexKind.SPECIES, speciesName) },
                            )
                            .padding(vertical = 4.dp),
                    )
                    types.forEach { type ->
                        Spacer(modifier = Modifier.width(6.dp))
                        TypeBadge(type)
                    }
                }
                CloseButton(onClose)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                val currentSprite = sprite
                if (currentSprite != null) {
                    Image(
                        bitmap = currentSprite,
                        contentDescription = null,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(88.dp),
                    )
                } else {
                    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                        MonoLabel("?", color = MonoText, fontSize = 24.sp)
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    MonoLabel("Lv ${mon.level}", color = MonoText, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val itemName = gameData.itemName(mon.heldItemId)
                        DexLink(
                            label = "Item",
                            value = itemName,
                            // "None" is the absence of an item, not an item to
                            // look up -- leave it inert rather than firing a
                            // request that can only 404.
                            enabled = mon.heldItemId != 0,
                            onClick = { dexQuery = DexQuery(DexKind.ITEM, itemName) },
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val abilityName = gameData.abilityName(mon.abilityId)
                        DexLink(
                            label = "Ability",
                            value = abilityName,
                            enabled = abilityName.isNotBlank(),
                            onClick = { dexQuery = DexQuery(DexKind.ABILITY, abilityName) },
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    PixelHpBar(
                        fraction = if (mon.maxHp > 0) mon.currentHp.toFloat() / mon.maxHp else 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    MonoLabel("HP ${mon.currentHp}/${mon.maxHp}", color = MonoText, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            MonoLabel("STATS", color = MonoTextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            // Prev/next sit right on the stats line rather than floating
            // somewhere else on screen, so they read as bracketing the exact
            // numbers they page through, and land at the screen's left/right
            // edges since this row is the only one not padded-and-centered.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavArrowButton(direction = NavDirection.PREVIOUS, onClick = onPrevious)
                StatsRow(
                    modifier = Modifier.weight(1f),
                    stats = listOf(
                        StatEntry("ATK", mon.attack, compareAgainst?.attack, statStages?.getOrNull(0)),
                        StatEntry("DEF", mon.defense, compareAgainst?.defense, statStages?.getOrNull(1)),
                        StatEntry("SPD", mon.speed, compareAgainst?.speed, statStages?.getOrNull(2)),
                        StatEntry("SP.ATK", mon.spAttack, compareAgainst?.spAttack, statStages?.getOrNull(3)),
                        StatEntry("SP.DEF", mon.spDefense, compareAgainst?.spDefense, statStages?.getOrNull(4)),
                    ),
                )
                NavArrowButton(direction = NavDirection.NEXT, onClick = onNext)
            }

            Spacer(modifier = Modifier.height(14.dp))

            MonoLabel("MOVES", color = MonoTextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            val rows = mon.moves.mapIndexed { i, moveId -> moveId to mon.pp.getOrElse(i) { 0 } }.chunked(2)
            rows.forEachIndexed { rowIndex, rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowSlots.forEach { (moveId, pp) ->
                        if (moveId != 0) {
                            val moveName = gameData.moveName(moveId)
                            MoveCard(
                                name = moveName,
                                type = gameData.moveType(moveId),
                                category = gameData.moveCategory(moveId),
                                power = gameData.movePower(moveId),
                                accuracy = gameData.moveAccuracy(moveId),
                                pp = pp,
                                ppMax = gameData.ppMax(moveId),
                                onClick = { dexQuery = DexQuery(DexKind.MOVE, moveName) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (rowIndex != rows.lastIndex) Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))

            MonoLabel("WEAKNESSES", color = MonoTextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            TypeBadgeRow(weaknesses)
            Spacer(modifier = Modifier.height(10.dp))
            MonoLabel("RESISTS", color = MonoTextMuted, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            TypeBadgeRow(resists)

            Spacer(modifier = Modifier.height(12.dp))
        }

        dexQuery?.let { query ->
            DexPopup(
                query = query,
                result = dexResult,
                onRetry = { dexAttempt++ },
                onDismiss = { dexQuery = null },
            )
        }
    }
}

/**
 * One stat: its label, this Pokemon's value, the opposing Pokemon's value for
 * the same stat when a comparison is being shown ([opposing] null means no
 * comparison, and the stat renders plain), and its live battle stage
 * ([stage] null or 0 shows no badge -- 0 is neutral, so it would be a no-op
 * label anyway).
 */
private data class StatEntry(val label: String, val value: Int, val opposing: Int?, val stage: Int? = null)

/**
 * Red where this Pokemon's stat beats the opposing one, green where it loses,
 * plain white when they tie or there is nothing to compare against.
 *
 * The colours read from the *player's* point of view, since this comparison
 * is only ever shown on an opponent's card: a stat the opponent wins is a
 * threat (red), one they lose is an opening (green).
 */
private fun statCompareColor(value: Int, opposing: Int?): Color = when {
    opposing == null || value == opposing -> MonoText
    value > opposing -> StatWorse
    else -> StatBetter
}

private val StatBetter = Color(0xFF4ADE68)
private val StatWorse = Color(0xFFF87171)

/**
 * Applies a battle stat-stage multiplier to a base stat value, matching the
 * game's own formula: (2+stage)/2 for a boost, 2/(2-stage) for a drop -- e.g.
 * +1 is 1.5x (a 50% increase), +2 is 2x, -1 is 2/3, -2 is 0.5x. Null or 0
 * (neutral) returns [base] unchanged.
 */
private fun applyStatStage(base: Int, stage: Int?): Int {
    if (stage == null || stage == 0) return base
    val multiplier = if (stage > 0) (2f + stage) / 2f else 2f / (2f - stage)
    return Math.round(base * multiplier)
}

/** All stats spread evenly across the full width, each as a compact label/value pair. */
@Composable
private fun StatsRow(stats: List<StatEntry>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        stats.forEach { entry ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel(entry.label, color = MonoTextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(2.dp))
                // Compares the stage-adjusted value, not the raw base stat --
                // otherwise a -1 that drops this stat below the opponent's (or
                // a +1 that pushes it above) leaves the colour stuck on
                // whatever it was before the stage applied.
                val adjusted = applyStatStage(entry.value, entry.stage)
                MonoLabel(
                    adjusted.toString(),
                    color = statCompareColor(adjusted, entry.opposing),
                    fontSize = 15.sp,
                )
                val stage = entry.stage
                if (stage != null && stage != 0) {
                    MonoLabel(
                        text = if (stage > 0) "+$stage" else stage.toString(),
                        color = MonoText,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }
}



/**
 * A "Label: Value" pair where the value opens its description.
 *
 * The only affordance is the value being full-strength white against the muted
 * label -- Mono has no colour or underline to spend on marking it tappable, and
 * a brightness step is the one signal that fits. [enabled] false renders the
 * whole thing muted and inert, which is what "Item: None" wants.
 */
@Composable
private fun DexLink(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // Padding is on the row rather than the text so the tap target is
            // comfortably bigger than the 11sp glyphs it wraps.
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoLabel("$label: ", color = MonoTextMuted, fontSize = 11.sp)
        MonoLabel(value, color = if (enabled) MonoText else MonoTextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit) {
    MonoLabel(
        text = "CLOSE",
        color = MonoAccent,
        fontSize = 14.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .padding(8.dp),
    )
}

private enum class NavDirection { PREVIOUS, NEXT }

// A plain chevron outline -- "<" / ">" as two single-pixel diagonal strokes
// meeting at a point, no fill and no shaft/dash through it. 4x7, same blocky
// NES pixel style as the hub's heart/wrench.
private val NAV_ARROW_LEFT_ROWS = listOf(
    "0001",
    "0010",
    "0100",
    "1000",
    "0100",
    "0010",
    "0001",
)
private val NAV_ARROW_RIGHT_ROWS = NAV_ARROW_LEFT_ROWS.map { it.reversed() }

/**
 * Prev/next arrow, replicating [PokemonDetailScreen]'s existing edge-tap
 * zones as a real, visible pixel button instead of an invisible margin --
 * same result, but discoverable and precisely aligned to the stats line.
 * The tap target is deliberately larger than the drawn glyph (44dp square
 * vs a ~12dp-wide icon) so it stays easy to hit without the arrow itself
 * looking oversized next to the stat numbers it sits beside.
 */
@Composable
private fun NavArrowButton(direction: NavDirection, onClick: () -> Unit) {
    val rows = if (direction == NavDirection.PREVIOUS) NAV_ARROW_LEFT_ROWS else NAV_ARROW_RIGHT_ROWS
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PixelIcon(rows, Modifier.size(width = 12.dp, height = 21.dp))
    }
}

package com.gbapal.companion.ui.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.memory.MemoryMap
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
                    MonoLabel(displayName.uppercase(), color = MonoText, fontSize = 20.sp)
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
                    MonoLabel(
                        "Item: ${gameData.itemName(mon.heldItemId)}   Ability: ${gameData.abilityName(mon.abilityId)}",
                        color = MonoTextMuted,
                        fontSize = 11.sp,
                    )
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
                            MoveCard(
                                name = gameData.moveName(moveId),
                                type = gameData.moveType(moveId),
                                category = gameData.moveCategory(moveId),
                                power = gameData.movePower(moveId),
                                accuracy = gameData.moveAccuracy(moveId),
                                pp = pp,
                                ppMax = gameData.ppMax(moveId),
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

private val TypeColors = mapOf(
    "Normal" to Color(0xFFA8A878),
    "Fire" to Color(0xFFF08030),
    "Water" to Color(0xFF6890F0),
    "Electric" to Color(0xFFF8D030),
    "Grass" to Color(0xFF78C850),
    "Ice" to Color(0xFF98D8D8),
    "Fighting" to Color(0xFFC03028),
    "Poison" to Color(0xFFA040A0),
    "Ground" to Color(0xFFE0C068),
    "Flying" to Color(0xFFA890F0),
    "Psychic" to Color(0xFFF85888),
    "Bug" to Color(0xFFA8B820),
    "Rock" to Color(0xFFB8A038),
    "Ghost" to Color(0xFF705898),
    "Dragon" to Color(0xFF7038F8),
    "Dark" to Color(0xFF705848),
    "Steel" to Color(0xFFB8B8D0),
    "Fairy" to Color(0xFFEE99AC),
)

private fun typeColor(type: String): Color = TypeColors[type] ?: MonoTextMuted

private val TypeAbbrev = mapOf(
    "Normal" to "NOR",
    "Fire" to "FIR",
    "Water" to "WAT",
    "Electric" to "ELE",
    "Grass" to "GRA",
    "Ice" to "ICE",
    "Fighting" to "FIG",
    "Poison" to "POI",
    "Ground" to "GRD",
    "Flying" to "FLY",
    "Psychic" to "PSY",
    "Bug" to "BUG",
    "Rock" to "ROC",
    "Ghost" to "GHO",
    "Dragon" to "DRA",
    "Dark" to "DAR",
    "Steel" to "STE",
    "Fairy" to "FAI",
)

/**
 * Standard (Gen 6+, Fairy-inclusive) type chart, keyed by DEFENDING type ->
 * (ATTACKING type -> damage multiplier). Only non-1x entries are listed;
 * anything absent is assumed neutral (1x).
 */
private val TypeChart: Map<String, Map<String, Float>> = mapOf(
    "Normal" to mapOf("Fighting" to 2f, "Ghost" to 0f),
    "Fire" to mapOf("Water" to 2f, "Ground" to 2f, "Rock" to 2f, "Fire" to 0.5f, "Grass" to 0.5f, "Ice" to 0.5f, "Bug" to 0.5f, "Steel" to 0.5f, "Fairy" to 0.5f),
    "Water" to mapOf("Electric" to 2f, "Grass" to 2f, "Fire" to 0.5f, "Water" to 0.5f, "Ice" to 0.5f, "Steel" to 0.5f),
    "Electric" to mapOf("Ground" to 2f, "Electric" to 0.5f, "Flying" to 0.5f, "Steel" to 0.5f),
    "Grass" to mapOf("Fire" to 2f, "Ice" to 2f, "Poison" to 2f, "Flying" to 2f, "Bug" to 2f, "Water" to 0.5f, "Electric" to 0.5f, "Grass" to 0.5f, "Ground" to 0.5f),
    "Ice" to mapOf("Fire" to 2f, "Fighting" to 2f, "Rock" to 2f, "Steel" to 2f, "Ice" to 0.5f),
    "Fighting" to mapOf("Flying" to 2f, "Psychic" to 2f, "Fairy" to 2f, "Bug" to 0.5f, "Rock" to 0.5f, "Dark" to 0.5f),
    "Poison" to mapOf("Ground" to 2f, "Psychic" to 2f, "Grass" to 0.5f, "Fighting" to 0.5f, "Poison" to 0.5f, "Bug" to 0.5f, "Fairy" to 0.5f),
    "Ground" to mapOf("Water" to 2f, "Grass" to 2f, "Ice" to 2f, "Poison" to 0.5f, "Rock" to 0.5f, "Electric" to 0f),
    "Flying" to mapOf("Electric" to 2f, "Ice" to 2f, "Rock" to 2f, "Grass" to 0.5f, "Fighting" to 0.5f, "Bug" to 0.5f, "Ground" to 0f),
    "Psychic" to mapOf("Bug" to 2f, "Ghost" to 2f, "Dark" to 2f, "Fighting" to 0.5f, "Psychic" to 0.5f),
    "Bug" to mapOf("Fire" to 2f, "Flying" to 2f, "Rock" to 2f, "Grass" to 0.5f, "Fighting" to 0.5f, "Ground" to 0.5f),
    "Rock" to mapOf("Water" to 2f, "Grass" to 2f, "Fighting" to 2f, "Ground" to 2f, "Steel" to 2f, "Normal" to 0.5f, "Fire" to 0.5f, "Poison" to 0.5f, "Flying" to 0.5f),
    "Ghost" to mapOf("Ghost" to 2f, "Dark" to 2f, "Poison" to 0.5f, "Bug" to 0.5f, "Normal" to 0f, "Fighting" to 0f),
    "Dragon" to mapOf("Ice" to 2f, "Dragon" to 2f, "Fairy" to 2f, "Fire" to 0.5f, "Water" to 0.5f, "Grass" to 0.5f, "Electric" to 0.5f),
    "Dark" to mapOf("Fighting" to 2f, "Bug" to 2f, "Fairy" to 2f, "Ghost" to 0.5f, "Dark" to 0.5f, "Psychic" to 0f),
    "Steel" to mapOf("Fire" to 2f, "Fighting" to 2f, "Ground" to 2f, "Normal" to 0.5f, "Grass" to 0.5f, "Ice" to 0.5f, "Flying" to 0.5f, "Psychic" to 0.5f, "Bug" to 0.5f, "Rock" to 0.5f, "Dragon" to 0.5f, "Steel" to 0.5f, "Fairy" to 0.5f, "Poison" to 0f),
    "Fairy" to mapOf("Poison" to 2f, "Steel" to 2f, "Fighting" to 0.5f, "Bug" to 0.5f, "Dark" to 0.5f, "Dragon" to 0f),
)

private fun typeEffectiveness(defenderTypes: List<String>, attackerType: String): Float =
    defenderTypes.fold(1f) { acc, defType -> acc * (TypeChart[defType]?.get(attackerType) ?: 1f) }

/**
 * Combines a (possibly dual-type) Pokemon's types into weakness/resist lists,
 * each paired with its actual multiplier (immunities count as resists, at
 * 0x). A dual-type Pokemon can be weak or resistant at more than one tier --
 * e.g. both types weak to the same attacking type stacks to 4x -- so the
 * multiplier is carried through rather than just a binary weak/resist flag.
 */
private fun weaknessesAndResists(type1: String?, type2: String?): Pair<List<Pair<String, Float>>, List<Pair<String, Float>>> {
    val types = listOfNotNull(type1, type2)
    if (types.isEmpty()) return emptyList<Pair<String, Float>>() to emptyList()
    val weak = mutableListOf<Pair<String, Float>>()
    val resist = mutableListOf<Pair<String, Float>>()
    TypeChart.keys.forEach { attackerType ->
        val multiplier = typeEffectiveness(types, attackerType)
        when {
            multiplier > 1f -> weak += attackerType to multiplier
            multiplier < 1f -> resist += attackerType to multiplier
        }
    }
    return weak to resist
}

/** "4x", "2x", "0.5x", "0.25x", or "IMMUNE" for 0x. */
private fun formatMultiplier(multiplier: Float): String = when (multiplier) {
    0f -> "IMMUNE"
    else -> "${if (multiplier == multiplier.toInt().toFloat()) multiplier.toInt().toString() else multiplier.toString()}x"
}

/**
 * A small filled colour chip -- not a frame/border, just the one allowed pop
 * of colour. [multiplier], when given (the weakness/resist lists), sits
 * underneath the type abbreviation in the same white but dimmed via alpha so
 * the 3-letter code stays the visual anchor and the number reads as
 * supporting detail. Null (the header's own-type badges) omits that line --
 * "this Pokemon is Ground/Steel" has no multiplier to show.
 */
@Composable
private fun TypeBadge(type: String, multiplier: Float? = null, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(typeColor(type), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (multiplier == null) {
            MonoLabel(TypeAbbrev[type] ?: type.take(3).uppercase(), color = Color.White, fontSize = 9.sp)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MonoLabel(TypeAbbrev[type] ?: type.take(3).uppercase(), color = Color.White, fontSize = 9.sp)
                MonoLabel(formatMultiplier(multiplier), color = Color.White.copy(alpha = 0.75f), fontSize = 7.sp)
            }
        }
    }
}

/** Wraps type badges into rows of 6 so long weakness/resist lists don't overflow the width. */
@Composable
private fun TypeBadgeRow(types: List<Pair<String, Float>>) {
    if (types.isEmpty()) {
        MonoLabel("NONE", color = MonoTextMuted, fontSize = 11.sp)
        return
    }
    val rows = types.chunked(6)
    rows.forEachIndexed { rowIndex, rowTypes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowTypes.forEach { (type, multiplier) -> TypeBadge(type, multiplier) }
        }
        if (rowIndex != rows.lastIndex) Spacer(modifier = Modifier.height(6.dp))
    }
}

/**
 * One cell of the 2x2 move grid: name (type-tinted) with a category icon
 * beside it, then type/PP, then power/accuracy. No border. [power] 0 and
 * [accuracy] 0 are both real in-data conventions for "doesn't apply" -- a
 * Status move with no power, and a move that can never miss -- so both
 * render as "--" rather than a misleading "0".
 */
@Composable
private fun MoveCard(
    name: String,
    type: String,
    category: String,
    power: Int,
    accuracy: Int,
    pp: Int,
    ppMax: Int,
    modifier: Modifier = Modifier,
) {
    val color = typeColor(type)
    Column(modifier = modifier.fillMaxWidth()) {
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
    Canvas(modifier = Modifier.size(11.dp)) {
        val cols = rows[0].length
        val pixelWidth = size.width / cols
        val pixelHeight = size.height / rows.size
        rows.forEachIndexed { row, line ->
            line.forEachIndexed { col, ch ->
                if (ch == '1') {
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
        Canvas(modifier = Modifier.size(width = 12.dp, height = 21.dp)) {
            val cols = rows[0].length
            val pixelWidth = size.width / cols
            val pixelHeight = size.height / rows.size
            rows.forEachIndexed { row, line ->
                line.forEachIndexed { col, ch ->
                    if (ch == '1') {
                        drawRect(
                            color = MonoAccent,
                            topLeft = Offset(col * pixelWidth, row * pixelHeight),
                            size = Size(pixelWidth, pixelHeight),
                        )
                    }
                }
            }
        }
    }
}

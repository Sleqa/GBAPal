package com.gbapal.companion.ui.opponent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.pokemon.BattleStat
import com.gbapal.companion.pokemon.BattleStats
import com.gbapal.companion.pokemon.GameData
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.ui.detail.MoveCard
import com.gbapal.companion.ui.detail.effectivenessAgainst
import com.gbapal.companion.ui.detail.StatusBadge
import com.gbapal.companion.ui.detail.MoveCategoryIcon
import com.gbapal.companion.ui.detail.TypeBadge
import com.gbapal.companion.ui.detail.TypeBadgeRow
import com.gbapal.companion.ui.detail.statCompareColor
import com.gbapal.companion.ui.detail.typeColor
import com.gbapal.companion.ui.detail.weaknessesAndResists
import com.gbapal.companion.ui.hub.HubMon
import com.gbapal.companion.ui.theme.FaintedSpriteFilter
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelIcon

/**
 * Both opposing Pokemon in a double battle, side by side.
 *
 * A compressed cousin of [com.gbapal.companion.ui.detail.PokemonDetailScreen]:
 * fitting two Pokemon on one screen means dropping what the player can already
 * see on the game's own screen (HP bars) and the static per-move reference text
 * (type name, power, accuracy) -- a move's type is already carried by the
 * colour of its name, and its category by the icon beside it. Everything else
 * keeps the single-battle screen's own text sizes so the two screens feel like
 * the same app.
 *
 * The layout mirrors the battle: [left] is the opposing Pokemon on the left of
 * the field and [right] the one on the right, with each name pushed out to its
 * own edge of the screen and CLOSE between them.
 */
@Composable
fun DoubleBattleScreen(
    left: HubMon?,
    right: HubMon?,
    gameData: GameData,
    client: RetroArchClient,
    map: MemoryMap,
    onClose: () -> Unit,
    /**
     * What each *position* is measured against -- the Pokemon standing
     * opposite it on the field. Positional rather than fixed to a particular
     * Pokemon, so that swapping the two columns re-pairs them, which is the
     * whole point of the swap control.
     */
    leftCompare: HubMon? = null,
    rightCompare: HubMon? = null,
    /**
     * Live stat stages (ATK/DEF/SPD/SP.ATK/SP.DEF, -6..+6) for each shown
     * Pokemon and for what it is compared against, so both sides of a
     * comparison are measured at their real current values.
     */
    leftStages: List<Int>? = null,
    rightStages: List<Int>? = null,
    /**
     * Whoever each column is facing across the field, for working out which of
     * its moves are super effective. Separate from the compare parameters
     * because those are switched off on the player's own side and by the
     * stat-compare setting, while this applies to both sides either way.
     */
    leftFacing: HubMon? = null,
    rightFacing: HubMon? = null,
    leftCompareStages: List<Int>? = null,
    rightCompareStages: List<Int>? = null,
    /** Label for the side-swap button, e.g. "YOUR MONS". Null hides it. */
    swapLabel: String? = null,
    onSwapSide: () -> Unit = {},
) {
    // Which Pokemon's full moveset is being shown, if any. Held here rather
    // than per column so opening one closes the other.
    // The Pokemon whose moves are open, paired with whoever it is facing --
    // the sheet needs both to mark the super-effective ones.
    var moveSheetFor by remember { mutableStateOf<Pair<HubMon, HubMon?>?>(null) }
    // Flips which column each Pokemon sits in. The compare targets stay put,
    // so this is what lets the player line either one up against either of
    // theirs rather than only the matchup the field happens to have.
    var positionsSwapped by remember { mutableStateOf(false) }

    // A Pokemon's own stages travel with it when the columns swap; the compare
    // targets stay anchored to their position, which is what re-pairs them.
    val leftMon = if (positionsSwapped) right else left
    val rightMon = if (positionsSwapped) left else right
    val leftMonStages = if (positionsSwapped) rightStages else leftStages
    val rightMonStages = if (positionsSwapped) leftStages else rightStages

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            // Same reasoning as the other full-screen overlays: without this a
            // tap on blank space falls through to the hub underneath.
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
            Header(leftMon, rightMon, gameData, onClose)
            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MonColumn(
                    leftMon, gameData, client, map,
                    mirrored = false,
                    compareAgainst = leftCompare,
                    stages = leftMonStages,
                    compareStages = leftCompareStages,
                    onMovesClick = { leftMon?.let { moveSheetFor = it to leftFacing } },
                    modifier = Modifier.weight(1f),
                )
                // The swap control lives in the gutter between the columns,
                // level with the SPD row, so it reads as acting on the two
                // stat blocks either side of it rather than on the screen.
                Column(
                    modifier = Modifier.width(SWAP_GUTTER_WIDTH),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(SWAP_BUTTON_TOP_OFFSET))
                    PixelIcon(
                        rows = SWAP_ICON_ROWS,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { positionsSwapped = !positionsSwapped },
                            )
                            // Tap target is bigger than the glyph, same trick
                            // as the detail screen's nav arrows.
                            .padding(8.dp)
                            // Matches the glyph's own 16:13 grid, so the loop
                            // isn't stretched out of shape.
                            .size(width = 24.dp, height = 19.dp),
                    )
                }
                MonColumn(
                    rightMon, gameData, client, map,
                    mirrored = true,
                    compareAgainst = rightCompare,
                    stages = rightMonStages,
                    compareStages = rightCompareStages,
                    onMovesClick = { rightMon?.let { moveSheetFor = it to rightFacing } },
                    modifier = Modifier.weight(1f),
                )
            }

            // Leaves room to scroll past the floating swap button below.
            if (swapLabel != null) Spacer(modifier = Modifier.height(56.dp))
        }

        // Centred like the header's CLOSE, so neither mirrored column has to
        // make room for it.
        if (swapLabel != null) {
            MonoLabel(
                text = "⇄ $swapLabel",
                color = MonoBg,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(MonoAccent, RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSwapSide,
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        moveSheetFor?.let { (mon, facing) ->
            MoveSheet(
                mon = mon,
                facing = facing,
                gameData = gameData,
                onDismiss = { moveSheetFor = null },
            )
        }
    }
}

/**
 * Both names with CLOSE between them. Each side takes an equal half and pushes
 * its content to the outer edge, so the left name lands exactly where the
 * single-battle screen puts it and the right is its mirror image.
 */
@Composable
private fun Header(left: HubMon?, right: HubMon?, gameData: GameData, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NameAndTypes(left, gameData, mirrored = false, modifier = Modifier.weight(1f))
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
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        NameAndTypes(right, gameData, mirrored = true, modifier = Modifier.weight(1f))
    }
}

/** "NAME [TYPE] [TYPE]", or the reverse when [mirrored], hugging its outer edge. */
@Composable
private fun NameAndTypes(
    mon: HubMon?,
    gameData: GameData,
    mirrored: Boolean,
    modifier: Modifier = Modifier,
) {
    val entry = mon?.let { gameData.entry(it.speciesId) }
    val types = listOfNotNull(entry?.type1, entry?.type2)
    val name = mon?.let { it.nickname.ifBlank { gameData.speciesName(it.speciesId) } } ?: "-"

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (mirrored) Arrangement.End else Arrangement.Start,
    ) {
        if (mirrored) {
            types.forEach {
                TypeBadge(it)
                Spacer(modifier = Modifier.width(6.dp))
            }
        }
        MonoLabel(name.uppercase(), color = MonoText, fontSize = 20.sp)
        if (!mirrored) {
            types.forEach {
                Spacer(modifier = Modifier.width(6.dp))
                TypeBadge(it)
            }
        }
    }
}

/**
 * One opponent's stacked detail. [mirrored] flips the sprite to the far side
 * so the two columns read outwards from the middle of the screen rather than
 * both leaning the same way.
 */
@Composable
private fun MonColumn(
    mon: HubMon?,
    gameData: GameData,
    client: RetroArchClient,
    map: MemoryMap,
    mirrored: Boolean,
    compareAgainst: HubMon?,
    stages: List<Int>?,
    compareStages: List<Int>?,
    onMovesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mon == null) {
        Column(modifier = modifier) {
            MonoLabel("(none)", color = MonoTextMuted, fontSize = 13.sp)
        }
        return
    }

    val baseEntry = gameData.entry(mon.speciesId)
    val (weaknesses, resists) = remember(baseEntry?.type1, baseEntry?.type2) {
        weaknessesAndResists(baseEntry?.type1, baseEntry?.type2)
    }

    // Every section header, row and badge in this column hangs off this one
    // alignment, so the right-hand column is a true mirror of the left rather
    // than the same layout nudged sideways.
    Column(
        modifier = modifier,
        horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (mirrored) Arrangement.End else Arrangement.Start,
        ) {
            if (mirrored) {
                LevelAndTraits(mon, gameData, alignEnd = true, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(10.dp))
                MonSprite(mon, client, map)
            } else {
                MonSprite(mon, client, map)
                Spacer(modifier = Modifier.width(10.dp))
                LevelAndTraits(mon, gameData, alignEnd = false, modifier = Modifier.weight(1f))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        MonoLabel("STATS", color = MonoTextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        StatsBlock(
            mon, mirrored, compareAgainst, stages, compareStages,
            abilityName = gameData.abilityName(mon.abilityId),
            compareAbility = compareAgainst?.let { gameData.abilityName(it.abilityId) },
        )

        Spacer(modifier = Modifier.height(12.dp))
        MonoLabel("MOVES", color = MonoTextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        // The whole list is one tap target rather than each move being its
        // own: the popup shows all four regardless, so which one was pressed
        // would make no difference to what opens.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onMovesClick,
                ),
            horizontalAlignment = if (mirrored) Alignment.End else Alignment.Start,
        ) {
            mon.moves.filter { it != 0 }.forEach { moveId ->
                val label = @Composable {
                    MonoLabel(
                        gameData.moveName(moveId).uppercase(),
                        color = typeColor(gameData.moveType(moveId)),
                        fontSize = 13.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mirrored) {
                        MoveCategoryIcon(gameData.moveCategory(moveId))
                        Spacer(modifier = Modifier.width(5.dp))
                        label()
                    } else {
                        label()
                        Spacer(modifier = Modifier.width(5.dp))
                        MoveCategoryIcon(gameData.moveCategory(moveId))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        MonoLabel("WEAKNESSES", color = MonoTextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(5.dp))
        TypeBadgeRow(weaknesses, perRow = 4, alignEnd = mirrored)
        Spacer(modifier = Modifier.height(9.dp))
        MonoLabel("RESISTS", color = MonoTextMuted, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(5.dp))
        TypeBadgeRow(resists, perRow = 4, alignEnd = mirrored)
    }
}

@Composable
private fun LevelAndTraits(
    mon: HubMon,
    gameData: GameData,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoLabel("Lv ${mon.level}", color = MonoText, fontSize = 14.sp)
            mon.status?.let {
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(it)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        LabelledValue("Item", gameData.itemName(mon.heldItemId))
        LabelledValue("Ability", gameData.abilityName(mon.abilityId))
    }
}

/** Grey label, white value -- the value is the part worth reading at a glance. */
@Composable
private fun LabelledValue(label: String, value: String) {
    Row {
        MonoLabel("$label: ", color = MonoTextMuted, fontSize = 11.sp)
        MonoLabel(value, color = MonoText, fontSize = 11.sp)
    }
}

/**
 * All four moves in the single-battle screen's own full format -- type name,
 * category icon, PP, power and accuracy. That detail is dropped from the
 * columns behind this to make room for two Pokemon; this is where to get it
 * back without leaving the doubles view.
 */
@Composable
private fun MoveSheet(mon: HubMon, facing: HubMon?, gameData: GameData, onDismiss: () -> Unit) {
    val facingEntry = facing?.let { gameData.entry(it.speciesId) }
    val name = mon.nickname.ifBlank { gameData.speciesName(mon.speciesId) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoBg, RoundedCornerShape(4.dp))
                .border(1.dp, MonoTextMuted, RoundedCornerShape(4.dp))
                .padding(16.dp),
        ) {
            MonoLabel("MOVES", color = MonoTextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(3.dp))
            MonoLabel(name.uppercase(), color = MonoText, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(14.dp))

            val slots = mon.moves.mapIndexed { i, id -> id to mon.pp.getOrElse(i) { 0 } }
            slots.chunked(2).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { (moveId, pp) ->
                        if (moveId != 0) {
                            MoveCard(
                                name = gameData.moveName(moveId),
                                type = gameData.moveType(moveId),
                                category = gameData.moveCategory(moveId),
                                power = gameData.movePower(moveId),
                                accuracy = gameData.moveAccuracy(moveId),
                                pp = pp,
                                ppMax = gameData.ppMax(moveId),
                                onClick = {},
                                modifier = Modifier.weight(1f),
                                effectiveness = facingEntry?.let {
                                    effectivenessAgainst(it.type1, it.type2, gameData.moveType(moveId))
                                },
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (rowIndex != slots.chunked(2).lastIndex) Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MonoLabel(
                    text = "CLOSE",
                    color = MonoAccent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun MonSprite(mon: HubMon, client: RetroArchClient, map: MemoryMap) {
    var sprite by remember(mon.speciesId, map) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mon.speciesId, map) {
        sprite = SpriteAssets.romFrontSprite(client, map, mon.speciesId)
    }
    val current = sprite
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            filterQuality = FilterQuality.None,
            colorFilter = if (mon.currentHp == 0) FaintedSpriteFilter else null,
            modifier = Modifier.size(72.dp),
        )
    } else {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            MonoLabel("?", color = MonoText, fontSize = 20.sp)
        }
    }
}

/**
 * The five stats as label/value rows rather than the single-battle screen's
 * horizontal strip -- two of those side by side would be too narrow to read,
 * and stacking is what buys the room for a second Pokemon.
 *
 * Plain white, deliberately: with four Pokemon in play there is no single
 * opponent to measure against, and colouring against "whichever of yours wins
 * that stat" reads as a claim about a matchup that isn't necessarily the one
 * that will happen.
 */
@Composable
private fun StatsBlock(
    mon: HubMon,
    mirrored: Boolean,
    compareAgainst: HubMon?,
    stages: List<Int>?,
    compareStages: List<Int>?,
    abilityName: String?,
    compareAbility: String?,
) {
    val base = listOf(
        Triple("ATK", BattleStat.ATTACK, mon.attack to compareAgainst?.attack),
        Triple("DEF", BattleStat.DEFENSE, mon.defense to compareAgainst?.defense),
        Triple("SPD", BattleStat.SPEED, mon.speed to compareAgainst?.speed),
        Triple("SP.ATK", BattleStat.SP_ATTACK, mon.spAttack to compareAgainst?.spAttack),
        Triple("SP.DEF", BattleStat.SP_DEFENSE, mon.spDefense to compareAgainst?.spDefense),
    )
    base.forEachIndexed { i, (label, stat, values) ->
        val (rawValue, rawOpposing) = values
        val stage = stages?.getOrNull(i)
        // Both sides go through stages *and* status before comparing, so a
        // Swords Dance, a burn or a paralysis each move the colour the way
        // they actually move the matchup.
        val value = BattleStats.effective(rawValue, stage, stat, mon.status, abilityName)
        val opposing = rawOpposing?.let {
            BattleStats.effective(it, compareStages?.getOrNull(i), stat, compareAgainst?.status, compareAbility)
        }

        // Spread across a fraction of the column rather than all of it.
        // Full width pushed the label and its number to opposite edges with a
        // gulf between them; sitting them flush was too tight to scan as a
        // table. This lands between the two, and scales with the screen
        // instead of being a hardcoded gap.
        val labelCell = @Composable {
            MonoLabel(text = label, color = MonoTextMuted, fontSize = 11.sp)
        }
        // Stage and number stay adjacent as one unit on the inside edge, so
        // the modifier reads as belonging to the number it changed.
        val valueCell = @Composable {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stageLabel = @Composable {
                    if (stage != null && stage != 0) {
                        MonoLabel(
                            text = if (stage > 0) "+$stage" else "$stage",
                            color = MonoText,
                            fontSize = 10.sp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }
                if (!mirrored) stageLabel()
                MonoLabel(
                    text = value.toString(),
                    color = statCompareColor(value, opposing),
                    fontSize = 15.sp,
                )
                if (mirrored) {
                    Spacer(modifier = Modifier.width(4.dp))
                    if (stage != null && stage != 0) {
                        MonoLabel(
                            text = if (stage > 0) "+$stage" else "$stage",
                            color = MonoText,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(STATS_ROW_WIDTH_FRACTION),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (mirrored) {
                valueCell()
                labelCell()
            } else {
                labelCell()
                valueCell()
            }
        }
    }
}

/** How much of a column's width one stat row spans -- see [StatsBlock]. */
private const val STATS_ROW_WIDTH_FRACTION = 0.62f

/** Gutter between the two columns, sized to hold the swap icon. */
private val SWAP_GUTTER_WIDTH = 44.dp

// 16x13 repeat/loop glyph, in the same blocky style as the hub's heart and
// wrench: a closed circuit running right along the top and back left along the
// bottom, with an arrowhead at each far corner. Drawn with 2px strokes so it
// keeps its weight next to the heavier icons elsewhere -- the earlier 1px
// version read as thin and wiry beside them.
private val SWAP_ICON_ROWS = listOf(
    "0000000000011000",
    "0000000000011100",
    "0111111111111110",
    "0111111111111111",
    "0110000000011110",
    "0110000000011000",
    "0110000000000000",
    "0110000000000110",
    "0001100000011110",
    "1111111111111110",
    "1111111111111100",
    "0011100000000000",
    "0011000000000000",
)

/**
 * Drops the swap bubble level with the SPD row -- the middle of the five
 * stats. Approximate by design: matching the sprite/header/row heights above
 * it exactly would mean measuring the layout, and being a few pixels out here
 * costs nothing.
 */
private val SWAP_BUTTON_TOP_OFFSET = 152.dp

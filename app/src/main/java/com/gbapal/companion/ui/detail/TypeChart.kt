package com.gbapal.companion.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoTextMuted

/*
 * The type system: colours, abbreviations, the damage chart, and the badges
 * that render them. Pure game knowledge with no screen of its own, kept apart
 * from the detail screen so the chart can be read (and corrected) without
 * scrolling through layout code.
 */

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

internal fun typeColor(type: String): Color = TypeColors[type] ?: MonoTextMuted

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
internal fun weaknessesAndResists(type1: String?, type2: String?): Pair<List<Pair<String, Float>>, List<Pair<String, Float>>> {
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
internal fun TypeBadge(type: String, multiplier: Float? = null, modifier: Modifier = Modifier) {
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
internal fun TypeBadgeRow(types: List<Pair<String, Float>>) {
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

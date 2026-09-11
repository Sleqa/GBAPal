package com.gbapal.companion.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbapal.companion.pokemon.StatusCondition
import com.gbapal.companion.ui.theme.MonoLabel

/**
 * Colours for the major status conditions.
 *
 * This is the second sanctioned use of colour in Mono, after type colour (see
 * MonoTheme's doc). It earns it the same way: a status is something the player
 * needs to register instantly mid-turn, and the colours are the ones the games
 * themselves have used for decades, so they read without being learned.
 */
private val StatusColors = mapOf(
    StatusCondition.PARALYSIS to Color(0xFFD8B020),
    StatusCondition.BURN to Color(0xFFE0603C),
    StatusCondition.FREEZE to Color(0xFF68B8D8),
    StatusCondition.POISON to Color(0xFFA060A0),
    StatusCondition.TOXIC to Color(0xFF803870),
    StatusCondition.SLEEP to Color(0xFF8890A0),
)

/** Small filled chip naming a Pokemon's status, shaped like [TypeBadge] so the two sit together. */
@Composable
internal fun StatusBadge(status: StatusCondition, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(StatusColors[status] ?: Color.Gray, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoLabel(status.label, color = Color.White, fontSize = 9.sp)
    }
}

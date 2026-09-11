package com.gbapal.companion.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gbapal.companion.pokemon.GameData
import com.gbapal.companion.pokemon.SwapAdvisor
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelIcon
import kotlin.math.roundToInt

/**
 * The swap advisor's entry point: a small glyph that opens the ranking.
 *
 * Deliberately unobtrusive. The suggestion is an opinion built on a model of
 * the battle, not a reading of it like the rest of the app, so it stays behind
 * a tap rather than sitting on the screen next to measured facts.
 */
@Composable
internal fun SwapAdvisorButton(
    candidates: List<SwapAdvisor.Candidate>,
    opponentName: String,
    gameData: GameData,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) return
    var open by remember { mutableStateOf(false) }

    PixelIcon(
        rows = SWAP_ADVISOR_ICON,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { open = true },
            )
            // Tap target well beyond the glyph, which is tiny on purpose.
            .padding(8.dp)
            .size(14.dp),
        color = MonoAccent,
    )

    if (open) {
        SwapAdvisorDialog(
            candidates = candidates,
            opponentName = opponentName,
            gameData = gameData,
            onDismiss = { open = false },
        )
    }
}

/** An up arrow and a down arrow side by side: something goes out, something comes in. */
private val SWAP_ADVISOR_ICON = listOf(
    "0100010",
    "1110010",
    "1110010",
    "0100010",
    "0100111",
    "0100111",
    "0100010",
)

@Composable
private fun SwapAdvisorDialog(
    candidates: List<SwapAdvisor.Candidate>,
    opponentName: String,
    gameData: GameData,
    onDismiss: () -> Unit,
) {
    val best = candidates.first()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoBg, RoundedCornerShape(4.dp))
                .border(1.dp, MonoTextMuted, RoundedCornerShape(4.dp))
                .padding(16.dp),
        ) {
            MonoLabel("SWITCH IN VS ${opponentName.uppercase()}", color = MonoTextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(6.dp))
            MonoLabel(
                text = best.mon.nickname.ifBlank { gameData.speciesName(best.mon.speciesId) }.uppercase(),
                color = MonoText,
                fontSize = 20.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            MonoLabel(verdict(best), color = verdictColor(best), fontSize = 13.sp)

            // The one number worth spelling out, because it is the cost of
            // switching and the thing a player is most likely to misjudge.
            switchInLine(best, gameData)?.let {
                Spacer(modifier = Modifier.height(8.dp))
                MonoLabel(it, color = MonoTextMuted, fontSize = 11.sp)
            }

            if (candidates.size > 1) {
                Spacer(modifier = Modifier.height(14.dp))
                Column(
                    modifier = Modifier.heightIn(max = OTHERS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                ) {
                    candidates.drop(1).forEach { candidate ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            MonoLabel(
                                text = candidate.mon.nickname
                                    .ifBlank { gameData.speciesName(candidate.mon.speciesId) }
                                    .uppercase(),
                                color = MonoTextMuted,
                                fontSize = 12.sp,
                            )
                            MonoLabel(
                                text = shortVerdict(candidate),
                                color = verdictColor(candidate),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            // Deliberately short, and deliberately there. Everything else this
            // app shows is read out of the game; this is the one screen that
            // guesses, and it should not be mistaken for the others.
            MonoLabel(
                text = "A rough estimate. Items, abilities and move effects are not counted.",
                color = MonoTextMuted,
                fontSize = 9.sp,
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MonoLabel(
                    text = "CLOSE",
                    color = MonoAccent,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        )
                        .padding(6.dp),
                )
            }
        }
    }
}

private fun verdict(c: SwapAdvisor.Candidate): String {
    if (c.faintsOnEntry) return "Faints on the way in"
    val turns = c.turnsToKo ?: return "Can't damage it"
    if (!c.winsRace) return "Loses the exchange"
    val hp = (c.hpRemainingFraction * 100).roundToInt().coerceIn(0, 100)
    val hits = if (c.turnsToFall == null) "untouched" else "$hp% HP left"
    return "Wins in ${turns.turnWord()}, $hits"
}

private fun shortVerdict(c: SwapAdvisor.Candidate): String {
    if (c.faintsOnEntry) return "faints on entry"
    if (c.turnsToKo == null) return "no damage"
    if (!c.winsRace) return "loses"
    return "${c.turnsToKo}T / ${(c.hpRemainingFraction * 100).roundToInt().coerceIn(0, 100)}%"
}

private fun verdictColor(c: SwapAdvisor.Candidate): Color = when {
    !c.winsRace -> AdvisorBad
    c.hpRemainingFraction >= COMFORTABLE_HP -> AdvisorGood
    else -> AdvisorClose
}

/** What the incoming Pokemon is expected to eat on the way in. */
private fun switchInLine(c: SwapAdvisor.Candidate, gameData: GameData): String? {
    val hit = c.switchInHit ?: return null
    val move = gameData.moveName(hit.moveId)
    return "Likely takes $move on the way in, about ${hit.damage}."
}

private fun multiplierSuffix(multiplier: Float): String? = when {
    multiplier == 1f -> null
    multiplier == multiplier.toInt().toFloat() -> "${multiplier.toInt()}x"
    else -> "${multiplier}x"
}

private fun Int.turnWord(): String = if (this == 1) "1 turn" else "$this turns"

/** Keeps a full bench from pushing the caveat and CLOSE off a short screen. */
private val OTHERS_MAX_HEIGHT = 130.dp

/** Above this much HP left, a win counts as comfortable rather than close. */
private const val COMFORTABLE_HP = 0.5f

private val AdvisorGood = Color(0xFF4ADE68)
private val AdvisorClose = Color(0xFFE0C060)
private val AdvisorBad = Color(0xFFF87171)

/**
 * Bridges [GameData] to what [SwapAdvisor] needs.
 *
 * Kept here rather than inside the advisor so the maths stays free of the app's
 * data layer and can be tested against a handful of made-up moves. The type
 * chart comes from this package too, which is the other reason the seam sits on
 * this side of it.
 */
internal fun GameData.asBattleFacts(): SwapAdvisor.BattleFacts = object : SwapAdvisor.BattleFacts {
    override fun types(speciesId: Int): List<String> =
        entry(speciesId)?.let { listOfNotNull(it.type1, it.type2) } ?: emptyList()

    override fun movePower(moveId: Int) = this@asBattleFacts.movePower(moveId)
    override fun moveType(moveId: Int) = this@asBattleFacts.moveType(moveId)
    override fun moveCategory(moveId: Int) = this@asBattleFacts.moveCategory(moveId)
    override fun abilityName(abilityId: Int) = this@asBattleFacts.abilityName(abilityId)

    override fun effectiveness(attackType: String, defenderTypes: List<String>): Float =
        effectivenessAgainst(defenderTypes.getOrNull(0), defenderTypes.getOrNull(1), attackType)
}

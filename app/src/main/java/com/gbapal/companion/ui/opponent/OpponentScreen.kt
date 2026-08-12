package com.gbapal.companion.ui.opponent

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.hub.HubMon
import com.gbapal.companion.ui.hub.PartyGrid
import com.gbapal.companion.ui.hub.buildGameData
import com.gbapal.companion.ui.hub.readPartyMons
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val OPPONENT_POLL_INTERVAL_MS = 10_000L
// Matches HubScreen's PARTY_POLL_INTERVAL_BATTLE_MS -- HP/status changes
// fast enough mid-battle that the idle cadence above feels stale.
private const val OPPONENT_POLL_INTERVAL_BATTLE_MS = 2_000L

/** Which party's detail view is currently showing. */
private enum class Side { OPPONENT, PLAYER }

/**
 * Opponent party screen: the enemy party in the same three-centered-rows
 * grid as the hub. Tapping a mon opens the same detail screen used for the
 * player's own party. [onClose] is just navigation (tapping CLOSE hides this
 * overlay) -- it does NOT mean the battle is over. The real "are we in
 * battle" state and its own end-of-battle detection live in HubScreen now,
 * independent of whether this overlay happens to be visible, since closing
 * this screen must not be a way to fool the hub's heal-block into thinking
 * the battle ended.
 *
 * [activeOpponentSpeciesId] and [activePlayerSpeciesId], when the profile can
 * supply them, are whichever Pokemon on each side is actually sent out right
 * now (as opposed to just on the team) -- selecting that slot automatically
 * means opening this screen goes straight to the Pokemon being fought, and it
 * re-selects itself whenever that changes (a switch, or a faint into the next
 * one), even if the player had navigated to a different slot. [party] is the
 * player's own roster, needed to resolve activePlayerSpeciesId to a slot and
 * to show the player-side detail view when the swap button is used.
 *
 * [statCompareEnabled] turns on stat colouring on the opponent's detail view,
 * measured against whichever Pokemon the player currently has out. It only
 * takes effect once both sides' active Pokemon are actually known, since
 * there is nothing meaningful to compare against otherwise.
 *
 * [inBattle] speeds up the enemy-party poll while true. Not inferred from
 * this screen being open -- the hub's own OPPONENT button can open it to
 * browse a team outside of battle too, where the slow cadence is still right.
 *
 * [activeOpponentStatStages]/[activePlayerStatStages] are each side's live
 * battle stat stages (ATK/DEF/SPD/SP.ATK/SP.DEF, -6..+6), read from the same
 * gBattleMons struct as the active-species ids above. Only meaningful for
 * whichever Pokemon is actually the one out on the field, so they're only
 * ever passed down to the detail view when the mon being shown matches the
 * corresponding active species id.
 */
@Composable
fun OpponentScreen(
    map: MemoryMap,
    activeOpponentSpeciesId: Int? = null,
    party: List<HubMon> = emptyList(),
    activePlayerSpeciesId: Int? = null,
    activeOpponentStatStages: List<Int>? = null,
    activePlayerStatStages: List<Int>? = null,
    statCompareEnabled: Boolean = false,
    inBattle: Boolean = false,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    // Same data source as the hub: live ROM where the profile describes it,
    // bundled tables otherwise. Rebuilt on a profile swap.
    val gameData = remember(map) { buildGameData(context, client, map) }

    var opponents by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var viewingSide by remember { mutableStateOf(Side.OPPONENT) }
    var isStarted by remember { mutableStateOf(false) }
    // The species each auto-jump effect below has already acted on, so a
    // party-list refresh that only changed HP (a new list, same active
    // species) doesn't re-force the selection and silently undo the player
    // manually browsing to a different slot -- only an actual change in
    // who's out (a switch or faint) should jump the view.
    var lastAutoSelectedOpponent by remember { mutableStateOf<Int?>(null) }
    var lastAutoSelectedPlayer by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // readPartyMons prefetches gameData for whichever party it's decoding, but
    // that only ever covers the enemy side here -- [party] arrives already
    // decoded (HubScreen built it with its own, separate GameData instance),
    // so this screen's gameData would otherwise never learn the player's own
    // species/moves/items. Without this, viewing the player's own mon via the
    // swap button looks up a species gameData here never prefetched, which
    // falls through to the generic bundled table -- wrong for any profile
    // whose species numbering doesn't match that table's, which is exactly
    // what showed Sprigatito as the wrong type here despite the hub's own
    // (separately-prefetched) copy of the same species showing correctly.
    LaunchedEffect(party, map) {
        gameData.prefetch(
            speciesIds = party.map { it.speciesId },
            moveIds = party.flatMap { it.moves },
            itemIds = party.map { it.heldItemId },
        )
    }

    LaunchedEffect(isStarted, map) {
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            val updatedOpponents = readPartyMons(client, map.enemyParty, gameData)
            if (updatedOpponents != opponents) {
                opponents = updatedOpponents
            }
            delay(if (inBattle) OPPONENT_POLL_INTERVAL_BATTLE_MS else OPPONENT_POLL_INTERVAL_MS)
        }
    }

    // Jumps straight to whichever opponent slot is actually out, whenever
    // that changes -- battle start, a switch, or a faint into the next mon
    // all show up here as activeOpponentSpeciesId changing. Only acts while
    // actually viewing the opponent's side, so it doesn't yank the player
    // back mid-look at their own Pokemon via the swap button. Guarded by
    // lastAutoSelectedOpponent so this only fires once per species: without
    // it, [opponents] getting a new (structurally different) list every poll
    // just from HP ticking down would re-run this on every single poll and
    // silently snap the player back to the active mon a few seconds after
    // they'd manually browsed to a different opponent slot to inspect it.
    // Landing on the active mon *immediately when this side is switched to*
    // is handled separately, by the swap button itself. A species with no
    // match yet (list not loaded) leaves lastAutoSelectedOpponent untouched,
    // so it retries on the next poll instead of giving up.
    LaunchedEffect(activeOpponentSpeciesId, opponents, viewingSide) {
        if (viewingSide != Side.OPPONENT) return@LaunchedEffect
        val species = activeOpponentSpeciesId ?: return@LaunchedEffect
        if (species == lastAutoSelectedOpponent) return@LaunchedEffect
        val index = opponents.indexOfFirst { it.speciesId == species }
        if (index >= 0) {
            selectedSlot = index
            lastAutoSelectedOpponent = species
        }
    }

    // Mirrors the above for the player's side.
    LaunchedEffect(activePlayerSpeciesId, party, viewingSide) {
        if (viewingSide != Side.PLAYER) return@LaunchedEffect
        val species = activePlayerSpeciesId ?: return@LaunchedEffect
        if (species == lastAutoSelectedPlayer) return@LaunchedEffect
        val index = party.indexOfFirst { it.speciesId == species }
        if (index >= 0) {
            selectedSlot = index
            lastAutoSelectedPlayer = species
        }
    }

    val detailList = if (viewingSide == Side.OPPONENT) opponents else party

    // The player's Pokemon currently on the field, which is what an opponent's
    // stats get measured against. Requires both sides to be identified: the
    // opponent's active species proves a battle is actually under way, and the
    // player's resolves to the specific team member doing the comparing.
    val playerActiveMon = if (activeOpponentSpeciesId == null || activePlayerSpeciesId == null) {
        null
    } else {
        party.firstOrNull { it.speciesId == activePlayerSpeciesId }
    }
    // Only ever shown on the opponent's side -- comparing the player's own
    // Pokemon against itself would colour every stat neutrally anyway.
    val compareAgainst = playerActiveMon
        ?.takeIf { statCompareEnabled && viewingSide == Side.OPPONENT }

    // Whichever side's detail view is currently open, resolved to that side's
    // live stat stages -- but only when the mon actually being shown is the
    // active battler, not some other slot the player is just browsing.
    val detailStatStages = if (viewingSide == Side.OPPONENT) {
        activeOpponentStatStages?.takeIf { detailList.getOrNull(selectedSlot ?: -1)?.speciesId == activeOpponentSpeciesId }
    } else {
        activePlayerStatStages?.takeIf { detailList.getOrNull(selectedSlot ?: -1)?.speciesId == activePlayerSpeciesId }
    }

    // Outer Box stays unpadded so PokemonDetailScreen (a direct sibling here,
    // same pattern as HubScreen) gets the true full screen height instead of
    // being squeezed by this screen's own content padding.
    //
    // The empty-onClick consumes every tap across the full screen -- without
    // it, a background-only Box doesn't participate in hit testing at all, so
    // a tap over any part of this screen with no clickable of its own (e.g.
    // blank space in the party grid) falls straight through to HubScreen
    // underneath, silently triggering its heal/repel buttons mid-battle.
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
                .padding(14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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

            PartyGrid(
                mons = opponents,
                client = client,
                map = map,
                onSelect = {
                    viewingSide = Side.OPPONENT
                    selectedSlot = it
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        val detailMon = selectedSlot?.let { detailList.getOrNull(it) }
        if (detailMon != null) {
            PokemonDetailScreen(
                mon = detailMon,
                gameData = gameData,
                client = client,
                map = map,
                onClose = { selectedSlot = null },
                onPrevious = {
                    val slot = selectedSlot
                    if (slot != null && detailList.isNotEmpty()) {
                        selectedSlot = (slot - 1 + detailList.size) % detailList.size
                    }
                },
                onNext = {
                    val slot = selectedSlot
                    if (slot != null && detailList.isNotEmpty()) selectedSlot = (slot + 1) % detailList.size
                },
                compareAgainst = compareAgainst,
                statStages = detailStatStages,
            )

            // Quick swap between the two active battlers -- shown for any
            // opponent Pokemon being viewed, not just the auto-pulled-up
            // active one, as long as the profile can actually say who the
            // player's active Pokemon is (without that there's nowhere for
            // the button to jump to). Jumps immediately to whichever mon is
            // currently active on the *other* side, rather than relying on
            // the reactive follow-effects above -- those are guarded against
            // re-firing for a species they've already jumped to (so they
            // don't undo manual browsing), which would otherwise make a
            // same-species swap-back a no-op.
            if (activePlayerSpeciesId != null) {
                MonoLabel(
                    text = if (viewingSide == Side.OPPONENT) "⇄ YOUR MON" else "⇄ OPPONENT",
                    color = MonoBg,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .background(MonoAccent, RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (viewingSide == Side.OPPONENT) {
                                    val index = party.indexOfFirst { it.speciesId == activePlayerSpeciesId }
                                    if (index >= 0) {
                                        viewingSide = Side.PLAYER
                                        selectedSlot = index
                                        lastAutoSelectedPlayer = activePlayerSpeciesId
                                    }
                                } else {
                                    val index = opponents.indexOfFirst { it.speciesId == activeOpponentSpeciesId }
                                    if (index >= 0) {
                                        viewingSide = Side.OPPONENT
                                        selectedSlot = index
                                        lastAutoSelectedOpponent = activeOpponentSpeciesId
                                    }
                                }
                            },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

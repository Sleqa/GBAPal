package com.gbapal.companion.ui.opponent

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
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.gbapal.companion.pokemon.GameData
import com.gbapal.companion.pokemon.SavedTeam
import com.gbapal.companion.pokemon.SwapAdvisor
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.detail.asBattleFacts
import com.gbapal.companion.ui.hub.HubMon
import com.gbapal.companion.ui.hub.PARTY_GRID_BOTTOM_BAR
import com.gbapal.companion.ui.hub.PARTY_GRID_TOP_BAR
import com.gbapal.companion.ui.hub.PartyGrid
import com.gbapal.companion.ui.hub.buildGameData
import com.gbapal.companion.ui.hub.readPartyMons
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoTextMuted
import com.gbapal.companion.ui.theme.PixelIcon
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
/**
 * Ranked switch-ins against [target], or empty when the advisor does not apply.
 *
 * Remembered on everything the ranking reads, since it runs the damage formula
 * over every party member's moves against every one of the target's and would
 * otherwise redo that work on each recomposition -- of which there are many,
 * with HP polling a few times a second.
 */
@Composable
private fun swapCandidatesFor(
    target: HubMon?,
    viewingSide: Side,
    party: List<HubMon>,
    targetStages: List<Int>?,
    activePlayer: HubMon?,
    gameData: GameData,
): List<SwapAdvisor.Candidate> {
    if (target == null || viewingSide != Side.OPPONENT || party.isEmpty()) return emptyList()
    return remember(target, party, targetStages, activePlayer, gameData) {
        SwapAdvisor.rank(party, target, targetStages, activePlayer, gameData.asBattleFacts())
    }
}

/** The save/recall glyph's own 10x12 grid, so the arrow is not stretched. */
private val SAVE_ICON_WIDTH = 18.dp
private val SAVE_ICON_HEIGHT = 22.dp

/** The discard glyph is square, and smaller -- it is the rarer, heavier action. */
private val CLEAR_ICON_SIZE = 15.dp

@Composable
fun OpponentScreen(
    map: MemoryMap,
    activeOpponentSpeciesId: Int? = null,
    party: List<HubMon> = emptyList(),
    activePlayerSpeciesId: Int? = null,
    activeOpponentStatStages: List<Int>? = null,
    activePlayerStatStages: List<Int>? = null,
    statCompareEnabled: Boolean = false,
    /** Passed straight through to the detail view; see PokemonDetailScreen. */
    dexLookupEnabled: Boolean = true,
    inBattle: Boolean = false,
    /**
     * True while a double battle is under way, which swaps this screen for
     * [DoubleBattleScreen] so both opposing Pokemon are visible at once.
     * Always false for a profile with no battleTypeFlags anchor, which keeps
     * the single-battle behaviour it has always had.
     */
    isDoubleBattle: Boolean = false,
    /**
     * Which party slot each of the four battle slots was sent out from
     * (index 0/2 = player side, 1/3 = opponent side), from the
     * battlerPartyIndexes anchor. Resolving a battler this way rather than by
     * species is what keeps a team holding two of the same species correct.
     */
    battlerPartySlots: List<Int>? = null,
    /** Live stat stages per battle slot, same indexing as [battlerPartySlots]. */
    battlerStatStages: List<List<Int>?>? = null,
    /**
     * True when this screen was opened *by* a battle starting, which is the
     * only case that should jump straight to what's on the field. Opening it
     * by hand from the hub's OPPONENT button lands on the team instead --
     * that press means "show me their team", not "show me this fight".
     */
    openToActiveBattle: Boolean = false,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    // Same data source as the hub: live ROM where the profile describes it,
    // bundled tables otherwise. Rebuilt on a profile swap.
    val gameData = remember(map) { buildGameData(context, client, map) }

    var opponents by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    // The kept copy of an opponent team, reloaded from disk per profile so it
    // survives both this screen closing and the app restarting.
    var savedTeam by remember(map) { mutableStateOf(SavedTeam.load(context, map.id)) }
    // Whether the grid is currently showing that copy instead of live memory.
    var showingSavedTeam by remember(map) { mutableStateOf(false) }
    // The saved team, but only once its species/moves/items are in gameData's
    // caches. A HubMon stores ids, not names: types, moves, ability and item
    // are all looked up at draw time, and the live poll is what normally warms
    // those caches on its way past. A team restored from disk never went
    // through that, so drawing it straight away resolves every id against the
    // bundled fallback tables -- which use different species and move numbering
    // per game, and quietly render a plausible but wrong Pokemon.
    var savedTeamReady by remember(map) { mutableStateOf<List<HubMon>?>(null) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var viewingSide by remember { mutableStateOf(Side.OPPONENT) }
    var isStarted by remember { mutableStateOf(false) }
    // The species each auto-jump effect below has already acted on, so a
    // party-list refresh that only changed HP (a new list, same active
    // species) doesn't re-force the selection and silently undo the player
    // manually browsing to a different slot -- only an actual change in
    // who's out (a switch or faint) should jump the view.
    // Seeded to the current actives when opening by hand, so the auto-jump
    // effects below treat those as "already jumped to" and leave the team grid
    // alone; null when a battle opened this, so the jump happens as normal.
    var lastAutoSelectedOpponent by remember {
        mutableStateOf(if (openToActiveBattle) null else activeOpponentSpeciesId)
    }
    var lastAutoSelectedPlayer by remember {
        mutableStateOf(if (openToActiveBattle) null else activePlayerSpeciesId)
    }
    // Doubles only: whether the active pair or the team grid is showing.
    var showActivePair by remember(isDoubleBattle) { mutableStateOf(openToActiveBattle) }

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

    // Same job for the saved team, and the reason it is published through a
    // second state rather than drawn directly: the list only becomes visible
    // once every id in it can resolve to a real name.
    LaunchedEffect(savedTeam, map) {
        val team = savedTeam
        if (team == null) {
            savedTeamReady = null
            return@LaunchedEffect
        }
        gameData.prefetch(
            speciesIds = team.map { it.speciesId },
            moveIds = team.flatMap { it.moves },
            itemIds = team.map { it.heldItemId },
        )
        // A fresh list instance, so this reads as a state change and the grid
        // actually redraws now that the caches behind it are warm.
        savedTeamReady = team.toList()
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

    // A double battle gets its own screen entirely. Taken before any of the
    // single-battle selection state below, since none of it applies: there is
    // no one "selected" Pokemon to page through when the point is to show both
    // at once. Falls through to the normal screen if the party slots aren't
    // readable, so a profile that only knows the DOUBLE flag still shows
    // something rather than an empty screen.
    if (isDoubleBattle && battlerPartySlots != null && showActivePair) {
        // Even battle slots are the player's side, odd are the opponent's.
        //
        // Matched on partySlot rather than by list position, because the lists
        // have their empty slots removed. A two-trainer double battle leaves a
        // hole in the enemy party (see HubMon.partySlot), and indexing straight
        // into the list there silently returns the wrong Pokemon or none.
        fun battlerAt(battler: Int): HubMon? =
            battlerPartySlots.getOrNull(battler)?.let { slot ->
                (if (battler % 2 == 0) party else opponents).firstOrNull { it.partySlot == slot }
            }

        val showingOpponents = viewingSide == Side.OPPONENT
        // The opponent's two battlers appear reversed from the player's side
        // of the field: battler 1 stands on the *right* of the screen and
        // battler 3 on the left, the mirror of the player's own 0-then-2. So
        // the opponent's columns are ordered 3, 1 to match what the game is
        // actually showing, while the player's stay 0, 2.
        val leftBattler = if (showingOpponents) 3 else 0
        val rightBattler = if (showingOpponents) 1 else 2
        // Each column is measured against whoever is standing opposite it.
        val leftOpposite = if (showingOpponents) 0 else 3
        val rightOpposite = if (showingOpponents) 2 else 1

        DoubleBattleScreen(
            left = battlerAt(leftBattler),
            right = battlerAt(rightBattler),
            leftStages = battlerStatStages?.getOrNull(leftBattler),
            rightStages = battlerStatStages?.getOrNull(rightBattler),
            // Only the opponent's stats get compared. On your own Pokemon a
            // red number would mean "worse than theirs", the opposite reading
            // to the same colour on the opponent's card -- so they stay white
            // rather than being ambiguous.
            // Always populated, on both sides -- unlike the compare values
            // below, which the stat-compare setting and the player's own side
            // switch off. Which of your moves hit hard is worth knowing either
            // way.
            leftFacing = battlerAt(leftOpposite),
            rightFacing = battlerAt(rightOpposite),
            leftCompare = if (statCompareEnabled && showingOpponents) battlerAt(leftOpposite) else null,
            rightCompare = if (statCompareEnabled && showingOpponents) battlerAt(rightOpposite) else null,
            leftCompareStages = if (statCompareEnabled && showingOpponents) battlerStatStages?.getOrNull(leftOpposite) else null,
            rightCompareStages = if (statCompareEnabled && showingOpponents) battlerStatStages?.getOrNull(rightOpposite) else null,
            gameData = gameData,
            client = client,
            map = map,
            swapLabel = if (showingOpponents) "YOUR MONS" else "OPPONENT",
            onSwapSide = {
                viewingSide = if (showingOpponents) Side.PLAYER else Side.OPPONENT
            },
            // Drops to the full team grid rather than all the way out to the
            // hub -- the grid is the more useful next step mid-battle, and the
            // hub is still one more CLOSE away. Clearing the selection (and
            // marking both actives as already-jumped-to) is what stops the
            // auto-jump effects from immediately opening a single Pokemon's
            // detail view over the grid we just asked for.
            onClose = {
                showActivePair = false
                selectedSlot = null
                lastAutoSelectedOpponent = activeOpponentSpeciesId
                lastAutoSelectedPlayer = activePlayerSpeciesId
            },
        )
        return
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
    LaunchedEffect(activeOpponentSpeciesId, opponents, viewingSide, showingSavedTeam) {
        if (viewingSide != Side.OPPONENT) return@LaunchedEffect
        // The saved team is a different list entirely, so a slot index chosen
        // from the live one would point at the wrong Pokemon -- and yanking the
        // player out of a team they deliberately opened is wrong regardless.
        if (showingSavedTeam) return@LaunchedEffect
        val species = activeOpponentSpeciesId ?: return@LaunchedEffect
        if (species == lastAutoSelectedOpponent) return@LaunchedEffect
        val index = opponents.indexOfFirst { it.speciesId == species }
        if (index >= 0) {
            selectedSlot = index
            lastAutoSelectedOpponent = species
        }
    }

    // Mirrors the above for the player's side.
    LaunchedEffect(activePlayerSpeciesId, party, viewingSide, showingSavedTeam) {
        if (viewingSide != Side.PLAYER) return@LaunchedEffect
        if (showingSavedTeam) return@LaunchedEffect
        val species = activePlayerSpeciesId ?: return@LaunchedEffect
        if (species == lastAutoSelectedPlayer) return@LaunchedEffect
        val index = party.indexOfFirst { it.speciesId == species }
        if (index >= 0) {
            selectedSlot = index
            lastAutoSelectedPlayer = species
        }
    }

    // What the opponent side actually shows: the saved snapshot when one is
    // open, otherwise live memory. Only the grid and the detail view follow
    // this -- everything about the live battle below (who is out, what the
    // stats compare against) keeps reading the real party, since the snapshot
    // says nothing about the fight currently happening.
    val shownOpponents = if (showingSavedTeam) savedTeamReady.orEmpty() else opponents
    val detailList = if (viewingSide == Side.OPPONENT) shownOpponents else party

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

    // Who the Pokemon on screen is up against, for move effectiveness only:
    // the player's active mon when reading an opponent, the opponent's active
    // mon when reading your own team.
    val facing = if (viewingSide == Side.OPPONENT) {
        playerActiveMon
    } else {
        activeOpponentSpeciesId?.let { id -> opponents.firstOrNull { it.speciesId == id } }
    }

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
        // The way back into the fight, so dropping to the team grid mid-battle
        // isn't a one-way trip. Only shown when there is actually something to
        // go back to -- an identified pair in a double, or an identified active
        // Pokemon in a single. Worked out here rather than beside the button
        // because the grid above has to know to leave room for it.
        val activeOpponentSlot = opponents.indexOfFirst { it.speciesId == activeOpponentSpeciesId }
        val canReturnToBattle =
            (isDoubleBattle && battlerPartySlots != null) || (selectedSlot == null && activeOpponentSlot >= 0)

        Column(
            modifier = Modifier
                .fillMaxSize()
                // Same insets as the hub, so the grid between them lines up.
                .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 6.dp),
        ) {
            // A Box rather than a SpaceBetween Row: the two sides differ in
            // width, so a middle child in a Row lands wherever the leftovers
            // put it rather than in the centre of the screen.
            Box(modifier = Modifier.fillMaxWidth().height(PARTY_GRID_TOP_BAR)) {
                // Save / recall / discard, top left. One button at a time,
                // except while a team is kept, when the discard sits under it.
                //
                // With nothing kept and nothing live to copy there is no action
                // to offer, so nothing is drawn rather than a dead button. That
                // is the out-of-battle case: an opponent party only exists
                // while a battle runs, which is exactly why saving it is worth
                // doing before it goes.
                val kept = savedTeam
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // With a team kept this stack is taller than the bar.
                        // Allowed to run past it into the space the grid leaves
                        // above its first row, rather than pushing the grid down
                        // and putting the opponent's Pokemon somewhere the
                        // player's never are.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                ) {
                    if (kept != null || opponents.isNotEmpty()) {
                        PixelIcon(
                            rows = if (kept == null) SAVE_TEAM_ICON_ROWS else RESTORE_TEAM_ICON_ROWS,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        if (kept == null) {
                                            SavedTeam.save(context, map.id, opponents)
                                            savedTeam = opponents
                                        } else {
                                            showingSavedTeam = !showingSavedTeam
                                            // The two lists differ in length,
                                            // so a slot picked in one means
                                            // nothing in the other.
                                            selectedSlot = null
                                        }
                                    },
                                )
                                // Tap target larger than the glyph, same trick
                                // as the detail screen's nav arrows.
                                .padding(8.dp)
                                .size(width = SAVE_ICON_WIDTH, height = SAVE_ICON_HEIGHT),
                        )
                    }
                    if (kept != null) {
                        PixelIcon(
                            rows = CLEAR_TEAM_ICON_ROWS,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        SavedTeam.clear(context, map.id)
                                        savedTeam = null
                                        showingSavedTeam = false
                                        selectedSlot = null
                                    },
                                )
                                .padding(8.dp)
                                .size(CLEAR_ICON_SIZE),
                            color = MonoTextMuted,
                        )
                    }
                }

                // Which of the two lists is on screen. Without this the saved
                // team is indistinguishable from the live one, and the whole
                // point of the snapshot is that it is frozen -- its HP bars
                // stopped moving the moment it was taken.
                if (showingSavedTeam) {
                    MonoLabel(
                        text = "SAVED TEAM",
                        color = MonoTextMuted,
                        fontSize = 13.sp,
                        // Shares CLOSE's padding so the two sit on a line.
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(8.dp),
                    )
                }

                MonoLabel(
                    text = "CLOSE",
                    color = MonoAccent,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        )
                        .padding(8.dp),
                )
            }

            PartyGrid(
                mons = shownOpponents,
                client = client,
                map = map,
                onSelect = {
                    viewingSide = Side.OPPONENT
                    selectedSlot = it
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Mirrors the hub's OPPONENT button: a real row at the foot of the
            // column, not an overlay. Its height is reserved whether or not the
            // button is showing, so the grid occupies the same band either way
            // -- and nothing can end up underneath it.
            Row(
                modifier = Modifier.fillMaxWidth().height(PARTY_GRID_BOTTOM_BAR),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (canReturnToBattle) {
                    MonoLabel(
                        text = "ACTIVE BATTLE",
                        color = MonoBg,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(MonoAccent, RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    // Going back to the fight means leaving the
                                    // snapshot; it is not part of the live battle.
                                    showingSavedTeam = false
                                    if (isDoubleBattle && battlerPartySlots != null) {
                                        showActivePair = true
                                    } else if (activeOpponentSlot >= 0) {
                                        viewingSide = Side.OPPONENT
                                        selectedSlot = activeOpponentSlot
                                        lastAutoSelectedOpponent = activeOpponentSpeciesId
                                    }
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        val detailMon = selectedSlot?.let { detailList.getOrNull(it) }
        if (detailMon != null) {
            PokemonDetailScreen(
                mon = detailMon,
                gameData = gameData,
                client = client,
                map = map,
                // Back to the team grid, and marked already-jumped-to so the
                // auto-jump doesn't pull this same Pokemon straight back up.
                onClose = {
                    selectedSlot = null
                    lastAutoSelectedOpponent = activeOpponentSpeciesId
                    lastAutoSelectedPlayer = activePlayerSpeciesId
                },
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
                facing = facing,
                statStages = detailStatStages,
                dexLookupEnabled = dexLookupEnabled,
                // Only against an opponent: ranking your own team against one
                // of its own members answers nothing. Uses the Pokemon actually
                // on screen rather than whoever is out, so a benched opponent
                // can be scouted the same way.
                swapCandidates = swapCandidatesFor(
                    target = detailMon,
                    viewingSide = viewingSide,
                    party = party,
                    targetStages = detailStatStages,
                    activePlayer = playerActiveMon,
                    gameData = gameData,
                ),
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

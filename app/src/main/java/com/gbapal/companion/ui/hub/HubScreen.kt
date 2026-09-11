package com.gbapal.companion.ui.hub

import android.content.Context
import android.os.BatteryManager
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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gbapal.companion.memory.Anchor
import com.gbapal.companion.memory.GameProfiles
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.memory.PartyLayout
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseGetStatusResponse
import com.gbapal.companion.network.parseReadCoreMemoryResponse
import com.gbapal.companion.pokemon.BaseStats
import com.gbapal.companion.pokemon.FireRedHeaderPointers
import com.gbapal.companion.pokemon.FireRedLiveData
import com.gbapal.companion.pokemon.GameData
import com.gbapal.companion.pokemon.Gen3Decrypt
import com.gbapal.companion.pokemon.MoveData
import com.gbapal.companion.pokemon.NameTables
import com.gbapal.companion.pokemon.PartyDecoder
import com.gbapal.companion.pokemon.PartySlot
import com.gbapal.companion.pokemon.RomDataReader
import com.gbapal.companion.pokemon.StatusCondition
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.opponent.OpponentScreen
import com.gbapal.companion.ui.settings.SettingsScreen
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

private const val PARTY_POLL_INTERVAL_MS = 10_000L
// Battle HP/status changes fast enough that the 10s idle cadence above
// feels stale watching a fight -- both the player's own party poll and
// the opponent screen's poll switch to this while inBattle is true.
private const val PARTY_POLL_INTERVAL_BATTLE_MS = 2_000L
private const val CLOCK_BATTERY_POLL_INTERVAL_MS = 15_000L
private const val GAME_DETECT_POLL_INTERVAL_MS = 20_000L
private const val PLAYER_MOVE_POLL_INTERVAL_MS = 1_000L

// Enough of struct BattlePokemon (see pokemon.h) to reach the end of
// statStages: species (0x00) through statStages[6] (0x1F), inclusive.
private const val BATTLE_MON_READ_SIZE = 0x20

// BATTLE_TYPE_DOUBLE from CFRU's include/constants/battle.h. It is the lowest
// bit of gBattleTypeFlags, which is why the battleTypeFlags anchor only needs
// to read one byte of that u32.
private const val BATTLE_TYPE_DOUBLE = 0x01

/** MAX_BATTLERS_COUNT from CFRU's include/constants/battle.h. */
private const val MAX_BATTLERS = 4

/**
 * How many consecutive "no battle" reads end a battle, for profiles with a
 * battleInProgress anchor. Two rather than one: a dropped UDP reply or a read
 * torn across a frame would otherwise close the battle view mid-fight, and the
 * cost of being right is one extra poll interval of delay.
 */
private const val BATTLE_END_CONFIRM_READS = 2

/**
 * Extracts the five battle stat stages this app displays -- ATK, DEF, SPD,
 * SP.ATK, SP.DEF, in that order -- from a gBattleMons[] struct read, as
 * -6..+6 relative to neutral. Offset 0x19 in CFRU's struct BattlePokemon is
 * a 7-byte statStages[ATK,DEF,SPEED,SPATK,SPDEF,ACC,EVASION] array, each
 * byte 0-12 with 6 as neutral; ACC/EVASION (the last two) are dropped since
 * this app doesn't show them as stats. Confirmed live 2026-08-09: using
 * Hammer Arm moved the user's own SPEED byte from 06 to 05 mid-battle,
 * matching its self-speed-drop effect exactly.
 */
private fun statStagesFromBattlerBytes(bytes: ByteArray): List<Int>? {
    if (bytes.size < BATTLE_MON_READ_SIZE) return null
    return (0x19..0x1D).map { (bytes[it].toInt() and 0xFF) - 6 }
}

data class HubMon(
    /**
     * Which party slot this came out of, 0-5.
     *
     * Not the same as its position in the list: empty slots are dropped, so the
     * two only agree when the party has no holes. A two-trainer double battle
     * is exactly when it has one -- the enemy party puts the first trainer in
     * slots 0-2 and the second in 3-5, leaving a gap whenever the first brought
     * fewer than three. gBattlerPartyIndexes names real slots, so anything
     * resolving a battler has to match on this rather than index the list.
     */
    val partySlot: Int,
    val speciesId: Int,
    val nickname: String,
    val level: Int,
    val currentHp: Int,
    val maxHp: Int,
    val attack: Int,
    val defense: Int,
    val spAttack: Int,
    val spDefense: Int,
    val speed: Int,
    val heldItemId: Int,
    val abilityId: Int,
    val moves: List<Int>,
    val pp: List<Int>,
    /** Major status condition, or null when healthy. */
    val status: StatusCondition? = null,
)

/**
 * Assembles the data source for one profile: the live ROM reader when the
 * profile describes its tables, over the bundled tables as a fallback.
 */
internal fun buildGameData(context: Context, client: RetroArchClient, map: MemoryMap): GameData {
    // The profile's own addresses only apply when it is confirmed to describe
    // the ROM actually loaded -- otherwise they belong to a different game and
    // reading through them decodes the wrong bytes as if they meant something.
    val reader = if (map.matchesLoadedRom && map.dataTables.isNotEmpty()) {
        RomDataReader(client, map.dataTables)
    } else {
        null
    }
    // ROM-agnostic fallback for the CFRU/DPE family, covering any hack that has
    // no profile of its own (or, same situation, whose profile hasn't matched
    // yet). See FireRedLiveData's doc for why this needs its own object rather
    // than just skipping straight to the bundled tables.
    val liveFallback = if (FireRedHeaderPointers.appliesTo(map.engine)) FireRedLiveData(client) else null
    return GameData(
        names = NameTables.load(context),
        bundledStats = BaseStats.load(context),
        bundledMoves = MoveData.load(context),
        reader = reader,
        typeNames = map.typeNames,
        liveFallback = liveFallback,
    )
}

/** Reads [anchor]'s value as a single unsigned byte, or null on any failure. */
private suspend fun readAnchorByte(client: RetroArchClient, anchor: Anchor): Int? {
    val result = client.readCoreMemory(anchor.address, anchor.size)
    return (result as? RetroArchClient.Result.Success)
        ?.let { parseReadCoreMemoryResponse(it.response) }
        ?.firstOrNull()
        ?.let { it.toInt() and 0xFF }
}

/** Reads all of [anchor]'s bytes, for the anchors that are a small array rather than one value. */
private suspend fun readAnchorBytes(client: RetroArchClient, anchor: Anchor): ByteArray? {
    val result = client.readCoreMemory(anchor.address, anchor.size)
    return (result as? RetroArchClient.Result.Success)
        ?.let { parseReadCoreMemoryResponse(it.response) }
        ?.takeIf { it.size >= anchor.size }
}

internal suspend fun readPartyMons(
    client: RetroArchClient,
    layout: PartyLayout,
    gameData: GameData,
): List<HubMon> {
    val totalLength = layout.slotStride * layout.slotCount
    val response = client.readCoreMemory(layout.firstSlotAddress, totalLength)
    val partyBytes = (response as? RetroArchClient.Result.Success)
        ?.let { parseReadCoreMemoryResponse(it.response) }
        ?.takeIf { it.size >= totalLength }
        ?: return emptyList()

    // Decode every slot first, then fetch the game data all of them need in one
    // pass, so the per-species/per-move ROM reads happen once rather than being
    // interleaved (and repeated) per slot.
    val decoded = mutableListOf<Triple<Int, PartySlot, Gen3Decrypt.Decoded>>()
    for (slot in 0 until layout.slotCount) {
        val offset = slot * layout.slotStride
        val bytes = partyBytes.copyOfRange(offset, offset + layout.slotStride)
        val stats = PartyDecoder.decode(bytes) ?: continue
        if (!stats.looksValid) continue
        val fields = Gen3Decrypt.decode(bytes) ?: continue
        decoded += Triple(slot, stats, fields)
    }

    gameData.prefetch(
        speciesIds = decoded.map { it.third.speciesId },
        moveIds = decoded.flatMap { it.third.moves.toList() },
        itemIds = decoded.map { it.third.heldItemId },
    )

    return decoded.map { (slot, stats, fields) ->
        HubMon(
            partySlot = slot,
            speciesId = fields.speciesId,
            nickname = fields.nickname,
            level = stats.level,
            currentHp = stats.currentHp,
            maxHp = stats.maxHp,
            attack = stats.attack,
            defense = stats.defense,
            spAttack = stats.spAttack,
            spDefense = stats.spDefense,
            speed = stats.speed,
            heldItemId = fields.heldItemId,
            abilityId = gameData.abilityIdFor(
                fields.speciesId, stats.personality,
                fields.hiddenAbilityFlag, fields.abilityNum,
            ),
            moves = fields.moves.toList(),
            pp = fields.pp.toList(),
            status = StatusCondition.from(stats.status),
        )
    }
}

/**
 * Fully heals every occupied party slot in place: sets currentHp = maxHp,
 * zeroes the status-condition field (clears sleep/poison/burn/freeze/paralysis),
 * and tops up each move's PP to its base max (from [gameData] -- doesn't
 * account for PP Up bonuses, since those aren't tracked anywhere in this app
 * yet, so a PP-Upped move heals to its un-boosted max rather than its true
 * max). Matches what a Pokemon Center heal does. Re-reads the party fresh
 * rather than reusing the polled [HubMon] list, since that list drops empty
 * slots and would misalign slot addresses if one exists before the end of
 * the party.
 */
internal suspend fun healParty(client: RetroArchClient, layout: PartyLayout, gameData: GameData) {
    val totalLength = layout.slotStride * layout.slotCount
    val response = client.readCoreMemory(layout.firstSlotAddress, totalLength)
    val partyBytes = (response as? RetroArchClient.Result.Success)
        ?.let { parseReadCoreMemoryResponse(it.response) }
        ?.takeIf { it.size >= totalLength }
        ?: return

    for (slot in 0 until layout.slotCount) {
        val offset = slot * layout.slotStride
        val bytes = partyBytes.copyOfRange(offset, offset + layout.slotStride)
        val stats = PartyDecoder.decode(bytes) ?: continue
        if (!stats.looksValid) continue

        val slotAddress = layout.firstSlotAddress + offset
        val maxHpBytes = byteArrayOf(
            (stats.maxHp and 0xFF).toByte(),
            ((stats.maxHp shr 8) and 0xFF).toByte(),
        )
        client.writeCoreMemory(slotAddress + PartyDecoder.OFF_CUR_HP, maxHpBytes)
        client.writeCoreMemory(slotAddress + PartyDecoder.OFF_STATUS, ByteArray(4))

        val decoded = Gen3Decrypt.decode(bytes)
        if (decoded != null) {
            // Goes through buildPpWrite rather than poking OFF_PP directly: on a
            // game whose party block is encrypted, those bytes are ciphertext,
            // and writing plain PP over them would both scramble the moves and
            // break the struct's checksum -- which is exactly what makes a
            // Pokemon turn into a Bad EGG.
            val newPp = IntArray(4) { i -> gameData.ppMax(decoded.moves[i]) }
            Gen3Decrypt.buildPpWrite(bytes, newPp, decoded.format)?.forEach { write ->
                client.writeCoreMemory(slotAddress + write.offset, write.bytes)
            }
        }
    }
}

@Composable
fun HubScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    val scope = rememberCoroutineScope()
    // Null only if no profile was discovered at all, which means the bundled
    // ones failed to package -- worth saying plainly rather than crashing on
    // a force-unwrap.
    var detectedProfile by remember { mutableStateOf(GameProfiles.default(context)) }
    val map = detectedProfile ?: run {
        MonoLabel("No game profiles found.", color = MonoText, fontSize = 14.sp)
        return
    }
    // The bundled tables are the shared fallback for anything a profile does
    // not describe; GameData prefers the live ROM over them. Rebuilt on a
    // profile swap so no cached value leaks between games.
    val gameData = remember(map) { buildGameData(context, client, map) }

    val battleCounterAnchor = remember(map) { map.anchors.firstOrNull { it.name == "totalBattleCounter" } }
    val repelAnchor = remember(map) { map.anchors.firstOrNull { it.name == "repelStepCount" } }
    // A direct "is a battle currently happening" boolean, where a profile has
    // one. Strictly better than the fallbacks below when available: it drives
    // both battle-start AND battle-end from one read, with no calibration
    // ambiguity (0 is unambiguously not-in-battle, nonzero unambiguously is),
    // unlike a counter that only signals a *change* or a coordinate that only
    // signals the player having taken a step.
    val battleActiveAnchor = remember(map) { map.anchors.firstOrNull { it.name == "battleActiveFlag" } }
    val battleInProgressAnchor = remember(map) { map.anchors.firstOrNull { it.name == "battleInProgress" } }
    // Doubles support, both optional: without them the app simply behaves as it
    // always did and shows the single-battle view, which is the right fallback
    // for a profile whose engine hasn't had these addresses found yet.
    val battleTypeFlagsAnchor = remember(map) { map.anchors.firstOrNull { it.name == "battleTypeFlags" } }
    val battlerPartyIndexesAnchor = remember(map) { map.anchors.firstOrNull { it.name == "battlerPartyIndexes" } }

    var party by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    var lastBattleCounter by remember { mutableStateOf<Int?>(null) }
    var battery by remember { mutableIntStateOf(batteryPercent(context)) }
    var time by remember { mutableStateOf(clockText()) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var showOpponentScreen by remember { mutableStateOf(false) }
    // Whether the opponent screen should open on what's on the field or on the
    // team. A battle starting means the fight; the hub's OPPONENT button means
    // the team.
    var openOpponentToBattle by remember { mutableStateOf(false) }
    // Which species is actually out on each side right now, for profiles
    // that describe activeBattlers (gBattleMons). Null whenever that isn't
    // known -- either the profile has no such table, or no battle is in
    // progress. See the LaunchedEffects below for how these are kept live.
    var activeOpponentSpecies by remember { mutableStateOf<Int?>(null) }
    var activePlayerSpecies by remember { mutableStateOf<Int?>(null) }
    // Live battle stat stages (ATK/DEF/SPD/SP.ATK/SP.DEF, -6..+6) for whichever
    // Pokemon is actually out on each side -- kept alongside the species reads
    // above since both come from the same gBattleMons struct. See
    // statStagesFromBattlerBytes for the struct layout this depends on.
    var activeOpponentStatStages by remember { mutableStateOf<List<Int>?>(null) }
    var activePlayerStatStages by remember { mutableStateOf<List<Int>?>(null) }
    // Whether this is a double battle, and which party slot each of the four
    // battle slots was sent out from (index 0/2 = player side, 1/3 = opponent).
    // Both null when the profile can't answer, which keeps the single view.
    var isDoubleBattle by remember { mutableStateOf(false) }
    var battlerPartySlots by remember { mutableStateOf<List<Int>?>(null) }
    // Stat stages for all four battle slots, so a double battle can show them
    // for every Pokemon on the field rather than just the lead pair.
    var battlerStatStages by remember { mutableStateOf<List<List<Int>?>?>(null) }
    // The real "are we in battle" state, separate from showOpponentScreen --
    // that flag is just overlay visibility, and tapping CLOSE on the opponent
    // screen must not be a way to fool the heal block into thinking the
    // battle ended. Only the player-movement check below clears this.
    var inBattle by remember { mutableStateOf(false) }
    var isStarted by remember { mutableStateOf(false) }
    var infiniteRepelEnabled by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var qolModsEnabled by remember { mutableStateOf(true) }
    var statCompareEnabled by remember { mutableStateOf(true) }
    // Defaults on: the lookup is the app's only network use, and it is opt-out
    // rather than opt-in because it is a plainly useful part of the detail view.
    var dexLookupEnabled by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isStarted, inBattle, map) {
        if (!isStarted) return@LaunchedEffect
        // A profile swap means a different battleCounterAnchor address entirely --
        // never compare a freshly-read byte from it against a leftover value from
        // the previous profile's (different) address.
        lastBattleCounter = null
        while (isActive) {
            val updatedParty = readPartyMons(client, map.party, gameData)
            if (updatedParty != party) {
                party = updatedParty
            }
            // Skipped entirely when battleActiveAnchor exists -- that path handles
            // battle-start on its own, and mixing the two would double-trigger.
            if (!inBattle && battleActiveAnchor == null) {
                val anchor = battleCounterAnchor
                if (anchor != null) {
                    val counterByte = readAnchorByte(client, anchor)
                    // First read after a (re)start just calibrates the baseline -- it must
                    // never trigger on its own, otherwise whatever value the counter already
                    // sits at when the hub loads (launch, or closing dev tools) looks like a
                    // change and pops the opponent screen even though no battle just began.
                    val previous = lastBattleCounter
                    if (counterByte != null) {
                        if (previous != null && counterByte != previous) {
                            showOpponentScreen = true
                            openOpponentToBattle = true
                            inBattle = true
                        }
                        lastBattleCounter = counterByte
                    }
                }
            }
            val repel = repelAnchor
            // Toggle-driven now -- while enabled this pins the counter at max every
            // tick (activating it even if no repel is currently running); turning
            // the toggle off just stops refilling and lets it count down naturally.
            if (repel != null && infiniteRepelEnabled && qolModsEnabled) {
                client.writeCoreMemory(repel.address, byteArrayOf(0xFA.toByte()))
            }
            delay(if (inBattle) PARTY_POLL_INTERVAL_BATTLE_MS else PARTY_POLL_INTERVAL_MS)
        }
    }

    // Battle-end detection. Runs independently of whether the opponent overlay
    // is currently visible, since closing it manually mid-battle must not look
    // like the battle ending.
    //
    // Two mechanisms, in preference order:
    //
    //  * battleInProgress, where the profile has one -- a value that is nonzero
    //    for exactly as long as a battle is running. This ends the battle when
    //    the battle actually ends.
    //  * Otherwise, the player taking a step in the overworld. This is a proxy
    //    rather than the real thing: it cannot notice the battle is over until
    //    the player moves, so standing still afterwards leaves the battle view
    //    up. It stays as the fallback because it needs no per-game discovery,
    //    only an overworldObjects address, and every profile has one of those.
    //
    // A profile's battleActiveFlag is deliberately not used here either way --
    // it is trusted only for the *start* of a battle, below. Emerald Imperium's
    // spends most of a battle reading 0, blipping up only while the game waits
    // for input, so ending on it would close the view mid-fight.
    LaunchedEffect(isStarted, inBattle, map) {
        if (!isStarted || !inBattle) return@LaunchedEffect

        if (battleInProgressAnchor != null) {
            // Only after two consecutive zero reads, so that a single dropped or
            // torn read cannot end a battle that is still going.
            var zeroReads = 0
            while (isActive) {
                val bytes = readAnchorBytes(client, battleInProgressAnchor)
                if (bytes != null) {
                    zeroReads = if (bytes.all { it.toInt() == 0 }) zeroReads + 1 else 0
                    if (zeroReads >= BATTLE_END_CONFIRM_READS) {
                        inBattle = false
                        showOpponentScreen = false
                        return@LaunchedEffect
                    }
                }
                delay(PLAYER_MOVE_POLL_INTERVAL_MS)
            }
            return@LaunchedEffect
        }

        // Skipped when the profile has no confirmed overworld address, rather
        // than reading a guessed one -- a wrong address yields arbitrary bytes,
        // which would look like the player constantly moving and end the battle
        // view immediately.
        val player = map.overworldObjects ?: return@LaunchedEffect
        var battleStartPos: Pair<Int, Int>? = null
        while (isActive) {
            val result = client.readCoreMemory(player.firstSlotAddress, player.slotStride)
            val bytes = (result as? RetroArchClient.Result.Success)
                ?.let { parseReadCoreMemoryResponse(it.response) }
            if (bytes != null && bytes.size >= 20) {
                fun s16(offset: Int): Int {
                    val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                    return v.toShort().toInt()
                }
                val pos = s16(16) to s16(18)
                val baseline = battleStartPos
                if (baseline == null) {
                    battleStartPos = pos
                } else if (pos != baseline) {
                    inBattle = false
                    showOpponentScreen = false
                }
            }
            delay(PLAYER_MOVE_POLL_INTERVAL_MS)
        }
    }

    // Battle-start detection via battleActiveAnchor, where a profile has one.
    // Start-only: this anchor's readings after the initial 0->nonzero
    // transition are never used to end the battle (see the battle-end effect
    // above for why) -- every live test on Emerald Imperium showed this
    // transition itself firing reliably right as a battle actually began, so
    // it stays trustworthy for that half of the job even though it isn't for
    // the other half.
    LaunchedEffect(isStarted, map) {
        val anchor = battleActiveAnchor ?: return@LaunchedEffect
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            val value = readAnchorByte(client, anchor)
            if (value != null && value != 0 && !inBattle) {
                inBattle = true
                showOpponentScreen = true
                openOpponentToBattle = true
            }
            delay(PLAYER_MOVE_POLL_INTERVAL_MS)
        }
    }

    // Tracks which opponent Pokemon is actually sent out, for profiles that
    // describe activeBattlers (gBattleMons) -- index 1 there is the
    // opponent's current battler, and unlike enemyParty it updates the
    // instant a switch or a faint-into-the-next-mon happens. Reading just its
    // species field is enough: OpponentScreen already decodes the full team
    // with correct moves/ability/nickname from enemyParty, so this only needs
    // to say *which* of those slots is the one currently out, and pop the
    // screen back open if the player had closed it (matching what a switch
    // or faint should do: bring the new Pokemon to their attention).
    LaunchedEffect(isStarted, inBattle, map) {
        val layout = map.activeBattlers
        if (!isStarted || !inBattle || layout == null) {
            activeOpponentSpecies = null
            activeOpponentStatStages = null
            return@LaunchedEffect
        }
        val opponentBattlerAddress = layout.firstSlotAddress + layout.slotStride
        while (isActive) {
            val result = client.readCoreMemory(opponentBattlerAddress, BATTLE_MON_READ_SIZE)
            val bytes = (result as? RetroArchClient.Result.Success)
                ?.let { parseReadCoreMemoryResponse(it.response) }
            val species = bytes?.takeIf { it.size >= 2 }
                ?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            if (species != null && species != 0 && species != activeOpponentSpecies) {
                activeOpponentSpecies = species
                showOpponentScreen = true
                openOpponentToBattle = true
            }
            activeOpponentStatStages = bytes?.let { statStagesFromBattlerBytes(it) }
            delay(PLAYER_MOVE_POLL_INTERVAL_MS)
        }
    }

    // Same idea as above but for the player's own active battler (index 0),
    // used only to feed the opponent screen's "swap to your Pokemon" button --
    // unlike the opponent side, a change here does not pop the screen open,
    // since the player already knows when they've switched their own mon.
    LaunchedEffect(isStarted, inBattle, map) {
        val layout = map.activeBattlers
        if (!isStarted || !inBattle || layout == null) {
            activePlayerSpecies = null
            activePlayerStatStages = null
            return@LaunchedEffect
        }
        while (isActive) {
            val result = client.readCoreMemory(layout.firstSlotAddress, BATTLE_MON_READ_SIZE)
            val bytes = (result as? RetroArchClient.Result.Success)
                ?.let { parseReadCoreMemoryResponse(it.response) }
            val species = bytes?.takeIf { it.size >= 2 }
                ?.let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
            if (species != null && species != 0) {
                activePlayerSpecies = species
            }
            activePlayerStatStages = bytes?.let { statStagesFromBattlerBytes(it) }
            delay(PLAYER_MOVE_POLL_INTERVAL_MS)
        }
    }

    // Single vs double, plus which party slot each battler came from.
    //
    // The DOUBLE bit is read rather than counting live battlers: the count
    // drops as Pokemon faint, so a double battle down to its last mon would
    // start looking like a single one and yank the player onto a different
    // screen mid-fight, whereas this flag stays set for the whole battle.
    LaunchedEffect(isStarted, inBattle, map) {
        if (!isStarted || !inBattle || battleTypeFlagsAnchor == null) {
            isDoubleBattle = false
            battlerPartySlots = null
            battlerStatStages = null
            return@LaunchedEffect
        }
        val battlers = map.activeBattlers
        while (isActive) {
            readAnchorByte(client, battleTypeFlagsAnchor)?.let { flags ->
                isDoubleBattle = (flags and BATTLE_TYPE_DOUBLE) != 0
            }
            battlerPartySlots = battlerPartyIndexesAnchor
                ?.let { readAnchorBytes(client, it) }
                ?.let { bytes -> List(bytes.size / 2) { i -> (bytes[i * 2].toInt() and 0xFF) or ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8) } }

            // All four battlers in one read rather than four, since they are
            // contiguous -- the whole point of a stride.
            battlerStatStages = battlers?.let { layout ->
                val bytes = client.readCoreMemory(layout.firstSlotAddress, layout.slotStride * MAX_BATTLERS)
                    .let { it as? RetroArchClient.Result.Success }
                    ?.let { parseReadCoreMemoryResponse(it.response) }
                bytes?.takeIf { it.size >= layout.slotStride * MAX_BATTLERS }?.let { all ->
                    List(MAX_BATTLERS) { i ->
                        statStagesFromBattlerBytes(all.copyOfRange(i * layout.slotStride, (i + 1) * layout.slotStride))
                    }
                }
            }
            delay(PLAYER_MOVE_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(isStarted) {
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            battery = batteryPercent(context)
            time = clockText()
            delay(CLOCK_BATTERY_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(isStarted) {
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            val status = (client.getStatus() as? RetroArchClient.Result.Success)
                ?.let { parseGetStatusResponse(it.response) }
            if (status != null) {
                val matched = GameProfiles.forCrc32(context, status.crc32)
                // Reassigns even when the id is unchanged: the starting profile
                // is the *untrusted* default (matchesLoadedRom = false), and a
                // genuine crc32 match -- even one that happens to resolve to the
                // same profile -- is what upgrades it to trusted. Cheap to
                // over-assign: MemoryMap is a data class, so Compose skips
                // recomposition when the value is unchanged.
                if (matched != null) {
                    detectedProfile = matched
                }
            }
            delay(GAME_DETECT_POLL_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(PARTY_GRID_TOP_BAR),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier)

            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(time, color = MonoText, fontSize = 15.sp)
                Spacer(modifier = Modifier.width(10.dp))
                BatteryIcon(percent = battery)
                Spacer(modifier = Modifier.width(12.dp))
                SettingsWrench(onClick = { showSettings = true })
            }
        }

        // Three rows of two, filling the screen: the middle row sits at the
        // vertical center and the top/bottom rows space out symmetrically
        // around it. Each row's pair is compressed toward the horizontal
        // center rather than spread to the edges.
        PartyGrid(
            mons = party,
            client = client,
            map = map,
            onSelect = { selectedSlot = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().height(PARTY_GRID_BOTTOM_BAR),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(
                text = "OPPONENT",
                color = MonoAccent,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            openOpponentToBattle = false
                            showOpponentScreen = true
                        },
                    )
                    .padding(8.dp),
            )
        }
    }

    // Bottom-left corner, stacked above the party grid/overlays: repel toggle
    // on top, heal heart below. Kept out of the centered OPPONENT/DEX row so
    // they read as always-available quick actions rather than navigation.
    // Hidden entirely when QOL Mods is switched off in settings.
    if (qolModsEnabled) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RepelToggle(
                enabled = infiniteRepelEnabled,
                onToggle = {
                    infiniteRepelEnabled = !infiniteRepelEnabled
                    val repel = repelAnchor
                    if (repel != null) {
                        // Turning on primes the counter immediately rather than
                        // waiting for the next party-poll tick to do it (that
                        // tick is also what keeps it pinned at 250 the whole
                        // time it's on). Turning off clears it to 0 instead of
                        // just leaving whatever step count happened to be left,
                        // so switching off reads as "repel is now off", not
                        // "repel will run out eventually".
                        val steps = if (infiniteRepelEnabled) 0xFA else 0x00
                        scope.launch { client.writeCoreMemory(repel.address, byteArrayOf(steps.toByte())) }
                    }
                },
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Blocked mid-battle so a heal can't be used as an in-battle move.
            // Gated on inBattle, not showOpponentScreen -- the overlay can be
            // dismissed with CLOSE while the battle is still going, and that must
            // not be a way to re-enable healing. Still up to one
            // PARTY_POLL_INTERVAL_MS poll behind the battle actually starting.
            HealHeart(
                enabled = !inBattle,
                onClick = { scope.launch { healParty(client, map.party, gameData) } },
            )
        }
    }

    if (showSettings) {
        SettingsScreen(
            qolModsEnabled = qolModsEnabled,
            onQolModsChange = { qolModsEnabled = it },
            statCompareEnabled = statCompareEnabled,
            onStatCompareChange = { statCompareEnabled = it },
            dexLookupEnabled = dexLookupEnabled,
            onDexLookupChange = { dexLookupEnabled = it },
            onClose = { showSettings = false },
        )
    }

    val detailMon = selectedSlot?.let { party.getOrNull(it) }
    if (detailMon != null) {
        PokemonDetailScreen(
            mon = detailMon,
            gameData = gameData,
            client = client,
            map = map,
            dexLookupEnabled = dexLookupEnabled,
            // Only the mon actually out on the field has meaningful stat
            // stages -- a benched party member is never mid-battle-affected.
            statStages = activePlayerStatStages?.takeIf { detailMon.speciesId == activePlayerSpecies },
            onClose = { selectedSlot = null },
            onPrevious = {
                val slot = selectedSlot
                if (slot != null && party.isNotEmpty()) selectedSlot = (slot - 1 + party.size) % party.size
            },
            onNext = {
                val slot = selectedSlot
                if (slot != null && party.isNotEmpty()) selectedSlot = (slot + 1) % party.size
            },
        )
    }

    if (showOpponentScreen) {
        OpponentScreen(
            map = map,
            activeOpponentSpeciesId = activeOpponentSpecies,
            party = party,
            activePlayerSpeciesId = activePlayerSpecies,
            activeOpponentStatStages = activeOpponentStatStages,
            activePlayerStatStages = activePlayerStatStages,
            statCompareEnabled = statCompareEnabled,
            dexLookupEnabled = dexLookupEnabled,
            inBattle = inBattle,
            isDoubleBattle = isDoubleBattle,
            battlerPartySlots = battlerPartySlots,
            battlerStatStages = battlerStatStages,
            openToActiveBattle = openOpponentToBattle,
            onClose = { showOpponentScreen = false },
        )
    }
    }
}


private fun batteryPercent(context: Context): Int {
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

private fun clockText(): String {
    val cal = Calendar.getInstance()
    val h = cal.get(Calendar.HOUR)
    val hour12 = if (h == 0) 12 else h
    val minute = cal.get(Calendar.MINUTE)
    val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
    return "%d:%02d %s".format(hour12, minute, ampm)
}

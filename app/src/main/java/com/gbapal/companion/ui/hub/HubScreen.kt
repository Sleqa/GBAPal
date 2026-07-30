package com.gbapal.companion.ui.hub

import android.content.Context
import android.os.BatteryManager
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gbapal.companion.memory.GameProfiles
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.memory.PartyLayout
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseGetStatusResponse
import com.gbapal.companion.network.parseReadCoreMemoryResponse
import com.gbapal.companion.pokemon.BaseStats
import com.gbapal.companion.pokemon.Gen3Decrypt
import com.gbapal.companion.pokemon.MoveData
import com.gbapal.companion.pokemon.NameTables
import com.gbapal.companion.pokemon.PartyDecoder
import com.gbapal.companion.pokemon.RomSpeciesData
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.opponent.OpponentScreen
import com.gbapal.companion.ui.dexnav.DexNavScreen
import com.gbapal.companion.ui.settings.SettingsScreen
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

private const val PARTY_POLL_INTERVAL_MS = 10_000L
private const val CLOCK_BATTERY_POLL_INTERVAL_MS = 15_000L
private const val GAME_DETECT_POLL_INTERVAL_MS = 20_000L
private const val PLAYER_MOVE_POLL_INTERVAL_MS = 1_000L

data class HubMon(
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
)

internal suspend fun readPartyMons(
    client: RetroArchClient,
    layout: PartyLayout,
    baseStats: BaseStats,
): List<HubMon> {
    val totalLength = layout.slotStride * layout.slotCount
    val response = client.readCoreMemory(layout.firstSlotAddress, totalLength)
    val partyBytes = (response as? RetroArchClient.Result.Success)
        ?.let { parseReadCoreMemoryResponse(it.response) }
        ?.takeIf { it.size >= totalLength }
        ?: return emptyList()

    val out = mutableListOf<HubMon>()
    for (slot in 0 until layout.slotCount) {
        val offset = slot * layout.slotStride
        val bytes = partyBytes.copyOfRange(offset, offset + layout.slotStride)
        val stats = PartyDecoder.decode(bytes) ?: continue
        if (!stats.looksValid) continue
        val decoded = Gen3Decrypt.decode(bytes) ?: continue
        out += HubMon(
            speciesId = decoded.speciesId,
            nickname = decoded.nickname,
            level = stats.level,
            currentHp = stats.currentHp,
            maxHp = stats.maxHp,
            attack = stats.attack,
            defense = stats.defense,
            spAttack = stats.spAttack,
            spDefense = stats.spDefense,
            speed = stats.speed,
            heldItemId = decoded.heldItemId,
            abilityId = RomSpeciesData.abilityIdFor(client, decoded.speciesId, stats.personality, decoded.hiddenAbilityFlag)
                ?: baseStats.abilityIdFor(decoded.speciesId, stats.personality, decoded.hiddenAbilityFlag),
            moves = decoded.moves.toList(),
            pp = decoded.pp.toList(),
        )
    }
    return out
}

/**
 * Fully heals every occupied party slot in place: sets currentHp = maxHp,
 * zeroes the status-condition field (clears sleep/poison/burn/freeze/paralysis),
 * and tops up each move's PP to its base max (from [moveData] -- doesn't
 * account for PP Up bonuses, since those aren't tracked anywhere in this app
 * yet, so a PP-Upped move heals to its un-boosted max rather than its true
 * max). Matches what a Pokemon Center heal does. Re-reads the party fresh
 * rather than reusing the polled [HubMon] list, since that list drops empty
 * slots and would misalign slot addresses if one exists before the end of
 * the party.
 */
internal suspend fun healParty(client: RetroArchClient, layout: PartyLayout, moveData: MoveData) {
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
            val ppBytes = ByteArray(4) { i -> moveData.ppMax(decoded.moves[i]).coerceIn(0, 255).toByte() }
            client.writeCoreMemory(slotAddress + Gen3Decrypt.OFF_PP, ppBytes)
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

@Composable
fun HubScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    val scope = rememberCoroutineScope()
    var gameProfile by remember { mutableStateOf(GameProfiles.default(context)) }
    val map = gameProfile.memoryMap
    val names = remember { NameTables.load(context) }
    val baseStats = remember { BaseStats.load(context) }
    val moveData = remember { MoveData.load(context) }

    val battleCounterAnchor = remember(map) { map.anchors.firstOrNull { it.name == "totalBattleCounter" } }
    val repelAnchor = remember(map) { map.anchors.firstOrNull { it.name == "repelStepCount" } }

    var party by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    var lastBattleCounter by remember { mutableStateOf<Int?>(null) }
    var battery by remember { mutableIntStateOf(batteryPercent(context)) }
    var time by remember { mutableStateOf(clockText()) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var showOpponentScreen by remember { mutableStateOf(false) }
    // The real "are we in battle" state, separate from showOpponentScreen --
    // that flag is just overlay visibility, and tapping CLOSE on the opponent
    // screen must not be a way to fool the heal block into thinking the
    // battle ended. Only the player-movement check below clears this.
    var inBattle by remember { mutableStateOf(false) }
    var isStarted by remember { mutableStateOf(false) }
    var infiniteRepelEnabled by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDexNav by remember { mutableStateOf(false) }
    var qolModsEnabled by remember { mutableStateOf(true) }

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
            val updatedParty = readPartyMons(client, map.party, baseStats)
            if (updatedParty != party) {
                party = updatedParty
            }
            if (!inBattle) {
                val anchor = battleCounterAnchor
                if (anchor != null) {
                    val result = client.readCoreMemory(anchor.address, anchor.size)
                    val counterByte = (result as? RetroArchClient.Result.Success)
                        ?.let { parseReadCoreMemoryResponse(it.response) }
                        ?.firstOrNull()
                        ?.let { it.toInt() and 0xFF }
                    // First read after a (re)start just calibrates the baseline -- it must
                    // never trigger on its own, otherwise whatever value the counter already
                    // sits at when the hub loads (launch, or closing dev tools) looks like a
                    // change and pops the opponent screen even though no battle just began.
                    val previous = lastBattleCounter
                    if (counterByte != null) {
                        if (previous != null && counterByte != previous) {
                            showOpponentScreen = true
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
            delay(PARTY_POLL_INTERVAL_MS)
        }
    }

    // Battle-end detection: runs independently of whether the opponent overlay
    // is currently visible, since closing it manually mid-battle must not look
    // like the battle ending. Once a battle starts, the first read here just
    // calibrates the player's position as a baseline; any change after that
    // means the player has taken a step post-battle, so the battle is over.
    LaunchedEffect(isStarted, inBattle, map) {
        if (!isStarted || !inBattle) return@LaunchedEffect
        val player = map.overworldObjects
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
                if (matched != null && matched.id != gameProfile.id) {
                    gameProfile = matched
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
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
        ) {
            MonoLabel(
                text = "OPPONENT",
                color = MonoAccent,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showOpponentScreen = true },
                    )
                    .padding(8.dp),
            )
            MonoLabel(
                text = "DEX",
                color = MonoAccent,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { showDexNav = true },
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
                    if (infiniteRepelEnabled) {
                        val repel = repelAnchor
                        if (repel != null) {
                            scope.launch { client.writeCoreMemory(repel.address, byteArrayOf(0xFA.toByte())) }
                        }
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
                onClick = { scope.launch { healParty(client, map.party, moveData) } },
            )
        }
    }

    if (showSettings) {
        SettingsScreen(
            qolModsEnabled = qolModsEnabled,
            onQolModsChange = { qolModsEnabled = it },
            onClose = { showSettings = false },
        )
    }

    if (showDexNav) {
        DexNavScreen(
            client = client,
            names = names,
            map = map,
            inBattle = inBattle,
            onClose = { showDexNav = false },
        )
    }

    val detailMon = selectedSlot?.let { party.getOrNull(it) }
    if (detailMon != null) {
        PokemonDetailScreen(
            mon = detailMon,
            names = names,
            moveData = moveData,
            baseStats = baseStats,
            client = client,
            map = map,
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
        OpponentScreen(map = map, onClose = { showOpponentScreen = false })
    }
    }
}

/**
 * Up to six Pokemon laid out as three centered rows of two: the middle row
 * sits at the vertical center of [modifier]'s available height, and the top
 * and bottom rows space out symmetrically to fill the rest of it. Within a
 * row, the pair is compressed toward the horizontal center rather than
 * spread across the full width.
 */
@Composable
internal fun PartyGrid(
    mons: List<HubMon>,
    client: RetroArchClient,
    map: MemoryMap,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        mons.chunked(2).forEachIndexed { rowIndex, rowMons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            ) {
                rowMons.forEachIndexed { colIndex, mon ->
                    MonEntry(mon, client, map) { onSelect(rowIndex * 2 + colIndex) }
                }
            }
        }
    }
}

@Composable
internal fun MonEntry(mon: HubMon, client: RetroArchClient, map: MemoryMap, onClick: () -> Unit) {
    var sprite by remember(mon.speciesId, map) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mon.speciesId, map) {
        sprite = SpriteAssets.romFrontSprite(client, map, mon.speciesId)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        val currentSprite = sprite
        if (currentSprite != null) {
            Image(
                bitmap = currentSprite,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(68.dp),
            )
        } else {
            Box(modifier = Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                MonoLabel("?", color = MonoTextMuted, fontSize = 20.sp)
            }
        }
        MonoLabel(mon.nickname.uppercase(), color = MonoText, fontSize = 15.sp)
        MonoLabel("Lv${mon.level}", color = MonoTextMuted, fontSize = 13.sp)
    }
}

// 9x5 pixel-art battery outline, same blocky style as the heart/wrench: a
// hollow body (cols 0-7) with a single-pixel cap at col 8, row 2. The hollow
// interior (cols 1-6, rows 1-3) is where the charge fill bars get drawn in,
// column by column, proportional to [percent].
private val BATTERY_PIXEL_ROWS = listOf(
    "011111100",
    "100000010",
    "100000011",
    "100000010",
    "011111100",
)
private val BATTERY_INTERIOR_COLS = 1..6
private val BATTERY_INTERIOR_ROWS = 1..3

/** Pixel battery glyph: outline always drawn, filled columns scale with [percent]. */
@Composable
private fun BatteryIcon(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 26.dp, height = 14.dp)) {
            val cols = BATTERY_PIXEL_ROWS[0].length
            val rows = BATTERY_PIXEL_ROWS.size
            val pixelWidth = size.width / cols
            val pixelHeight = size.height / rows
            BATTERY_PIXEL_ROWS.forEachIndexed { row, line ->
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
            val filledCols = (BATTERY_INTERIOR_COLS.count() * (percent.coerceIn(0, 100) / 100f)).toInt()
            for (i in 0 until filledCols) {
                val col = BATTERY_INTERIOR_COLS.first + i
                for (row in BATTERY_INTERIOR_ROWS) {
                    drawRect(
                        color = MonoAccent,
                        topLeft = Offset(col * pixelWidth, row * pixelHeight),
                        size = Size(pixelWidth, pixelHeight),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        MonoLabel("$percent%", color = MonoText, fontSize = 14.sp)
    }
}

/** Small on/off label for the infinite-repel toggle: accent when active, muted when off. */
@Composable
private fun RepelToggle(enabled: Boolean, onToggle: () -> Unit) {
    MonoLabel(
        text = if (enabled) "REPEL ●" else "REPEL ○",
        color = if (enabled) MonoAccent else MonoTextMuted,
        fontSize = 13.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(6.dp),
    )
}

// 7x6 pixel-art heart, NES-style: 1 = filled pixel, 0 = empty.
private val HEART_PIXEL_ROWS = listOf(
    "0110110",
    "1111111",
    "1111111",
    "0111110",
    "0011100",
    "0001000",
)
private val HeartRed = Color(0xFFE0303D)

/**
 * Pixel heart heal button. Greyed out while [enabled] is false (e.g. mid-battle) --
 * uses clickable's own `enabled` param rather than gating inside the onClick body,
 * so the button genuinely stops being a touch target at all rather than just
 * silently no-op-ing on tap.
 */
@Composable
private fun HealHeart(enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) HeartRed else MonoTextMuted
    Canvas(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        val cols = HEART_PIXEL_ROWS[0].length
        val rows = HEART_PIXEL_ROWS.size
        val pixelWidth = size.width / cols
        val pixelHeight = size.height / rows
        HEART_PIXEL_ROWS.forEachIndexed { row, line ->
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

// 8x8 pixel-art wrench, same blocky style as the heart: open jaw top-left,
// diagonal shaft down to the bottom-right.
private val WRENCH_PIXEL_ROWS = listOf(
    "00011000",
    "00111100",
    "01100110",
    "00110000",
    "00011000",
    "00110000",
    "01100000",
    "11000000",
)

/** Pixel wrench settings icon, top-right of the hub. Always enabled. */
@Composable
private fun SettingsWrench(onClick: () -> Unit) {
    Canvas(
        modifier = Modifier
            .size(22.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val cols = WRENCH_PIXEL_ROWS[0].length
        val rows = WRENCH_PIXEL_ROWS.size
        val pixelWidth = size.width / cols
        val pixelHeight = size.height / rows
        WRENCH_PIXEL_ROWS.forEachIndexed { row, line ->
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

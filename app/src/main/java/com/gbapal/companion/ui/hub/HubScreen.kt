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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gbapal.companion.memory.MemoryMap
import com.gbapal.companion.memory.PartyLayout
import com.gbapal.companion.network.RetroArchClient
import com.gbapal.companion.network.parseReadCoreMemoryResponse
import com.gbapal.companion.pokemon.BaseStats
import com.gbapal.companion.pokemon.Gen3Decrypt
import com.gbapal.companion.pokemon.MoveData
import com.gbapal.companion.pokemon.NameTables
import com.gbapal.companion.pokemon.PartyDecoder
import com.gbapal.companion.pokemon.SpriteAssets
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.opponent.OpponentScreen
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Calendar

private const val PARTY_POLL_INTERVAL_MS = 10_000L
private const val CLOCK_BATTERY_POLL_INTERVAL_MS = 15_000L

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
            abilityId = baseStats.abilityIdFor(decoded.speciesId, stats.personality, decoded.hiddenAbilityFlag),
            moves = decoded.moves.toList(),
            pp = decoded.pp.toList(),
        )
    }
    return out
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
fun HubScreen(onDevToolsRequested: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    val map = remember { MemoryMap.load(context) }
    val names = remember { NameTables.load(context) }
    val baseStats = remember { BaseStats.load(context) }
    val moveData = remember { MoveData.load(context) }

    val battleFlagAnchor = remember { map.anchors.firstOrNull { it.name == "battleStateFlag" } }

    var party by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    var lastBattleFlag by remember { mutableStateOf<Int?>(null) }
    var battery by remember { mutableIntStateOf(batteryPercent(context)) }
    var time by remember { mutableStateOf(clockText()) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var showOpponentScreen by remember { mutableStateOf(false) }
    var isStarted by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isStarted, showOpponentScreen) {
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            val updatedParty = readPartyMons(client, map.party, baseStats)
            if (updatedParty != party) {
                party = updatedParty
            }
            if (!showOpponentScreen) {
                val anchor = battleFlagAnchor
                if (anchor != null) {
                    val result = client.readCoreMemory(anchor.address, anchor.size)
                    val flagByte = (result as? RetroArchClient.Result.Success)
                        ?.let { parseReadCoreMemoryResponse(it.response) }
                        ?.firstOrNull()
                        ?.let { it.toInt() and 0xFF }
                        ?: 0
                    // First read after a (re)start just calibrates the baseline -- it must
                    // never trigger on its own, otherwise a stale nonzero value at cold
                    // start (e.g. right after launch or closing dev tools) looks like an
                    // edge and pops the opponent screen even though no battle just began.
                    val previous = lastBattleFlag
                    if (previous != null && previous == 0 && flagByte != 0) {
                        showOpponentScreen = true
                    }
                    lastBattleFlag = flagByte
                }
            }
            delay(PARTY_POLL_INTERVAL_MS)
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

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MonoBg)
            .padding(start = 18.dp, end = 18.dp, bottom = 18.dp, top = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoLabel(time, color = MonoText, fontSize = 15.sp)
            Spacer(modifier = Modifier.width(14.dp))
            BatteryIcon(percent = battery)
            Spacer(modifier = Modifier.width(14.dp))
            MonoLabel(
                text = "⚙",
                color = MonoAccent,
                fontSize = 22.sp,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDevToolsRequested,
                ),
            )
        }

        // Three rows of two, filling the screen: the middle row sits at the
        // vertical center and the top/bottom rows space out symmetrically
        // around it. Each row's pair is compressed toward the horizontal
        // center rather than spread to the edges.
        PartyGrid(
            mons = party,
            onSelect = { selectedSlot = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
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
                    .padding(10.dp),
            )
            MonoLabel(
                text = "DEX",
                color = MonoAccent,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(10.dp),
            )
        }
    }

    val detailMon = selectedSlot?.let { party.getOrNull(it) }
    if (detailMon != null) {
        PokemonDetailScreen(
            mon = detailMon,
            names = names,
            moveData = moveData,
            baseStats = baseStats,
            onClose = { selectedSlot = null },
        )
    }

    if (showOpponentScreen) {
        OpponentScreen(onClose = { showOpponentScreen = false })
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
                horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            ) {
                rowMons.forEachIndexed { colIndex, mon ->
                    MonEntry(mon) { onSelect(rowIndex * 2 + colIndex) }
                }
            }
        }
    }
}

@Composable
internal fun MonEntry(mon: HubMon, onClick: () -> Unit) {
    val context = LocalContext.current
    val sprite = remember(mon.speciesId) { SpriteAssets.frontSprite(context, mon.speciesId) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        if (sprite != null) {
            Image(
                bitmap = sprite,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(68.dp),
            )
        } else {
            Box(modifier = Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                MonoLabel("?", color = MonoTextMuted, fontSize = 20.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        MonoLabel(mon.nickname.uppercase(), color = MonoText, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(2.dp))
        MonoLabel("Lv${mon.level}", color = MonoTextMuted, fontSize = 13.sp)
    }
}

/** Battery glyph: white outline + cap, white fill sized by charge. */
@Composable
private fun BatteryIcon(percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 32.dp, height = 16.dp)) {
            val bodyWidth = size.width * 0.88f
            drawRoundRect(
                color = MonoAccent,
                size = Size(bodyWidth, size.height),
                cornerRadius = CornerRadius(3f, 3f),
                style = Stroke(width = 3f),
            )
            drawRoundRect(
                color = MonoAccent,
                topLeft = Offset(bodyWidth + 2f, size.height * 0.28f),
                size = Size(size.width - bodyWidth - 2f, size.height * 0.44f),
                cornerRadius = CornerRadius(2f, 2f),
            )
            val inset = 5f
            drawRect(
                color = MonoAccent,
                topLeft = Offset(inset, inset),
                size = Size(
                    (bodyWidth - inset * 2f) * (percent.coerceIn(0, 100) / 100f),
                    size.height - inset * 2f,
                ),
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        MonoLabel("$percent%", color = MonoText, fontSize = 14.sp)
    }
}

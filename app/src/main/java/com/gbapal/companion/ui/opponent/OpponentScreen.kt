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
import com.gbapal.companion.pokemon.BaseStats
import com.gbapal.companion.pokemon.MoveData
import com.gbapal.companion.pokemon.NameTables
import com.gbapal.companion.ui.detail.PokemonDetailScreen
import com.gbapal.companion.ui.hub.HubMon
import com.gbapal.companion.ui.hub.PartyGrid
import com.gbapal.companion.ui.hub.readPartyMons
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val OPPONENT_POLL_INTERVAL_MS = 10_000L

/**
 * Opponent party screen: the enemy party in the same three-centered-rows
 * grid as the hub. Tapping a mon opens the same detail screen used for the
 * player's own party. [onClose] is just navigation (tapping CLOSE hides this
 * overlay) -- it does NOT mean the battle is over. The real "are we in
 * battle" state and its own end-of-battle detection live in HubScreen now,
 * independent of whether this overlay happens to be visible, since closing
 * this screen must not be a way to fool the hub's heal-block into thinking
 * the battle ended.
 */
@Composable
fun OpponentScreen(map: MemoryMap, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { RetroArchClient() }
    val names = remember { NameTables.load(context) }
    val baseStats = remember { BaseStats.load(context) }
    val moveData = remember { MoveData.load(context) }

    var opponents by remember { mutableStateOf<List<HubMon>>(emptyList()) }
    var selectedSlot by remember { mutableStateOf<Int?>(null) }
    var isStarted by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        isStarted = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isStarted, map) {
        if (!isStarted) return@LaunchedEffect
        while (isActive) {
            val updatedOpponents = readPartyMons(client, map.enemyParty, baseStats)
            if (updatedOpponents != opponents) {
                opponents = updatedOpponents
            }
            delay(OPPONENT_POLL_INTERVAL_MS)
        }
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
                onSelect = { selectedSlot = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        val detailMon = selectedSlot?.let { opponents.getOrNull(it) }
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
                    if (slot != null && opponents.isNotEmpty()) {
                        selectedSlot = (slot - 1 + opponents.size) % opponents.size
                    }
                },
                onNext = {
                    val slot = selectedSlot
                    if (slot != null && opponents.isNotEmpty()) selectedSlot = (slot + 1) % opponents.size
                },
            )
        }
    }
}

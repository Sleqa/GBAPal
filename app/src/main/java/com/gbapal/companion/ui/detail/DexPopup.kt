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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gbapal.companion.network.DexKind
import com.gbapal.companion.network.DexResult
import com.gbapal.companion.ui.theme.MonoAccent
import com.gbapal.companion.ui.theme.MonoBg
import com.gbapal.companion.ui.theme.MonoLabel
import com.gbapal.companion.ui.theme.MonoText
import com.gbapal.companion.ui.theme.MonoTextMuted

/** What the description popup is currently looking up. */
data class DexQuery(val kind: DexKind, val title: String)

private val DexKind.label: String
    get() = when (this) {
        DexKind.ABILITY -> "ABILITY"
        DexKind.ITEM -> "ITEM"
        DexKind.MOVE -> "MOVE"
        DexKind.SPECIES -> "POKEDEX"
    }

/**
 * Modal description for one ability/item/move. [result] null means the lookup
 * is still in flight.
 *
 * Carries a thin border, unlike the rest of the app -- a pure-black panel over
 * a pure-black screen has no edge otherwise. Every other Mono rule still holds.
 */
@Composable
fun DexPopup(
    query: DexQuery,
    result: DexResult?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MonoBg, RoundedCornerShape(4.dp))
                .border(1.dp, MonoTextMuted, RoundedCornerShape(4.dp))
                .padding(16.dp),
        ) {
            MonoLabel(query.kind.label, color = MonoTextMuted, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(3.dp))
            MonoLabel(query.title.uppercase(), color = MonoText, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(12.dp))

            // Capped and scrollable rather than left to grow freely -- a
            // species entry runs to a description plus every evolution line,
            // which can run taller than the screen on a branching family like
            // Eevee, while a single ability's blurb never needs to scroll at
            // all.
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (result) {
                    null -> MonoLabel("Looking up...", color = MonoTextMuted, fontSize = 12.sp)

                    is DexResult.Found -> MonoLabel(result.text, color = MonoText, fontSize = 12.sp)

                    // Worth spelling out rather than showing a bare "not found":
                    // on a ROM hack this is the expected answer for anything the
                    // hack invented, not a sign anything is broken.
                    DexResult.NotFound -> MonoLabel(
                        "No entry for this one. It is probably custom to this ROM hack -- " +
                            "the Pokedex source only covers the official games.",
                        color = MonoTextMuted,
                        fontSize = 12.sp,
                    )

                    is DexResult.Error -> MonoLabel(
                        "Couldn't reach the Pokedex (${result.message}).",
                        color = MonoTextMuted,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Only an Error is worth retrying -- Found and NotFound are both
                // cached final answers, so a retry would change nothing.
                if (result is DexResult.Error) {
                    MonoLabel(
                        text = "RETRY",
                        color = MonoAccent,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onRetry,
                            )
                            .padding(8.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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

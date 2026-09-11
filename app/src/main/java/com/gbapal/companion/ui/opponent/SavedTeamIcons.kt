package com.gbapal.companion.ui.opponent

/*
 * The three states of the save-team control, as pixel grids in the same blocky
 * style as the app's other icons.
 *
 * SAVE and RESTORE are deliberately the same tray with the arrow reversed. The
 * button changes meaning in place -- press it once and it stops saving and
 * starts recalling -- and a mirrored arrow says that far more directly than two
 * unrelated glyphs would. Candidate designs using a filled tray or stacked
 * layers for the second state were drawn and looked at; both read as an
 * anonymous blob at the size this renders, while the reversed arrow is obvious.
 */

/** Arrow down into a tray: take a copy of this team. */
internal val SAVE_TEAM_ICON_ROWS = listOf(
    "0000110000",
    "0000110000",
    "0000110000",
    "0000110000",
    "1100110011",
    "0110110110",
    "0011111100",
    "0001111000",
    "0000110000",
    "1000000001",
    "1111111111",
    "1111111111",
)

/** The same tray, arrow up: bring the saved team back. */
internal val RESTORE_TEAM_ICON_ROWS = listOf(
    "0000110000",
    "0001111000",
    "0011111100",
    "0110110110",
    "1100110011",
    "0000110000",
    "0000110000",
    "0000110000",
    "0000000000",
    "1000000001",
    "1111111111",
    "1111111111",
)

/** Discard the saved team. */
internal val CLEAR_TEAM_ICON_ROWS = listOf(
    "1100000011",
    "1110000111",
    "0111001110",
    "0011111100",
    "0001111000",
    "0001111000",
    "0011111100",
    "0111001110",
    "1110000111",
    "1100000011",
)

package com.gbapal.companion.pokemon

import com.gbapal.companion.memory.FieldSpec

/**
 * Field offsets for record shapes that show up across many games, so a profile
 * can name one instead of restating it.
 *
 * These are conveniences, not assumptions. A profile may override any field, or
 * name no layout at all and declare every field itself -- which is what makes
 * an engine nobody has written a preset for still supportable by a profile
 * alone. Nothing in the reader treats an unknown layout as an error.
 *
 * Field names are the app's own vocabulary; the reader looks them up by name,
 * so a layout only has to provide the ones its game actually stores.
 */
object RecordLayouts {

    /**
     * Classic Gen 3 `gBaseStats` -- 28 bytes per species. Used by vanilla
     * FireRed/Emerald/Ruby and by binary-patch hacks built on them (CFRU and
     * friends), which keep the struct and just move or extend the table.
     *
     * Note the stat order on disk is not the order they are displayed in:
     * speed sits at offset 3, between defense and special attack.
     */
    private val GEN3_SPECIES = mapOf(
        "hp" to FieldSpec(offset = 0x00),
        "attack" to FieldSpec(offset = 0x01),
        "defense" to FieldSpec(offset = 0x02),
        "speed" to FieldSpec(offset = 0x03),
        "spAttack" to FieldSpec(offset = 0x04),
        "spDefense" to FieldSpec(offset = 0x05),
        "type1" to FieldSpec(offset = 0x06),
        "type2" to FieldSpec(offset = 0x07),
        "ability1" to FieldSpec(offset = 0x16),
        "ability2" to FieldSpec(offset = 0x17),
        // Vanilla leaves 0x1A as padding; Dynamic Pokemon Expansion reuses it
        // for the hidden ability, and reads 0 on games that do not.
        "hiddenAbility" to FieldSpec(offset = 0x1A),
    )

    /**
     * pokeemerald-expansion `gSpeciesInfo` -- one big struct per species that
     * absorbed the separate tables. The stat and type bytes stayed where
     * classic Gen 3 put them, but abilities widened to `u16` and the name and
     * sprite pointers moved inside the record, at offsets that differ between
     * expansion releases. A profile supplies those via overrides.
     */
    private val EXPANSION_SPECIES = GEN3_SPECIES + mapOf(
        "ability1" to FieldSpec(offset = 0x18, size = 2),
        "ability2" to FieldSpec(offset = 0x1A, size = 2),
        "hiddenAbility" to FieldSpec(offset = 0x1C, size = 2),
    )

    /** Classic Gen 3 `gBattleMoves` -- 12 bytes per move. */
    private val GEN3_MOVE = mapOf(
        "power" to FieldSpec(offset = 0x01),
        "type" to FieldSpec(offset = 0x02),
        "accuracy" to FieldSpec(offset = 0x03),
        "pp" to FieldSpec(offset = 0x04),
    )

    /**
     * As above, plus the physical/special/status byte that battle-engine hacks
     * add at offset 10. Without it, category has to be inferred from the move's
     * type, which is only correct for pre-Gen-4 games.
     */
    private val GEN3_MOVE_SPLIT = GEN3_MOVE + mapOf(
        "category" to FieldSpec(offset = 0x0A),
    )

    val PRESETS: Map<String, Map<String, FieldSpec>> = mapOf(
        "gen3-species" to GEN3_SPECIES,
        "expansion-species" to EXPANSION_SPECIES,
        "gen3-move" to GEN3_MOVE,
        "gen3-move-split" to GEN3_MOVE_SPLIT,
    )
}

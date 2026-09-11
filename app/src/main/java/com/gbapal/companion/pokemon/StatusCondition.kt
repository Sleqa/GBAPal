package com.gbapal.companion.pokemon

/**
 * A Pokemon's major status condition, decoded from the Gen 3 `status1` u32.
 *
 * Confirmed live 2026-08-21 on Unbound: paralysing an opposing Shellder set
 * 0x40 in *both* gBattleMons and that Pokemon's enemyParty struct, so the
 * party copy is current mid-battle and the app can read status from the party
 * data it already fetches rather than making a second read of the battle copy.
 */
enum class StatusCondition(val label: String) {
    SLEEP("SLP"),
    POISON("PSN"),
    BURN("BRN"),
    FREEZE("FRZ"),
    PARALYSIS("PAR"),
    TOXIC("TOX");

    companion object {
        // Masks from CFRU's include/constants/battle.h. Sleep is a turn
        // counter rather than a flag, so any of its low three bits being set
        // means asleep -- which is also why it has to be tested before the
        // single-bit conditions below it.
        private const val SLEEP_MASK = 0x07L
        private const val POISON_MASK = 0x08L
        private const val BURN_MASK = 0x10L
        private const val FREEZE_MASK = 0x20L
        private const val PARALYSIS_MASK = 0x40L
        private const val TOXIC_MASK = 0x80L

        /** Null when healthy. Fainting is not a status bit -- check HP for that. */
        fun from(status1: Long): StatusCondition? = when {
            status1 and SLEEP_MASK != 0L -> SLEEP
            status1 and TOXIC_MASK != 0L -> TOXIC
            status1 and POISON_MASK != 0L -> POISON
            status1 and BURN_MASK != 0L -> BURN
            status1 and FREEZE_MASK != 0L -> FREEZE
            status1 and PARALYSIS_MASK != 0L -> PARALYSIS
            else -> null
        }
    }
}

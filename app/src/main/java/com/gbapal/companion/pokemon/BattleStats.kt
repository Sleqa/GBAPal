package com.gbapal.companion.pokemon

/** The five stats this app shows, in the order the screens list them. */
enum class BattleStat { ATTACK, DEFENSE, SPEED, SP_ATTACK, SP_DEFENSE }

/**
 * Turns a Pokemon's stored base stat into what it is actually worth right now,
 * accounting for battle stat stages, its status condition, and the abilities
 * that change how a status is felt.
 *
 * Every multiplier here was read out of CFRU's own source rather than assumed
 * from the mainline games, because the two disagree: CFRU halves Speed on
 * paralysis (`speed /= 2` in battle_start_turn_start.c) where Gen 3 quartered
 * it, and only falls back to the quarter behind an `OLD_PARALYSIS_SPD_DROP`
 * build flag that the supported hacks do not set.
 */
object BattleStats {

    /**
     * Applies a stat stage, matching the game's own formula: (2+stage)/2 for a
     * boost, 2/(2-stage) for a drop -- so +1 is 1.5x, +2 is 2x, -1 is 2/3.
     *
     * Done in integer arithmetic, truncating, because that is what the game
     * does. Rounding instead lands a point above the real value often enough
     * to matter on a speed tie.
     */
    fun applyStage(base: Int, stage: Int?): Int {
        if (stage == null || stage == 0) return base
        return if (stage > 0) base * (2 + stage) / 2 else base * 2 / (2 - stage)
    }

    /**
     * How [status] scales [stat], given the holder's [abilityName].
     *
     * Freeze, sleep, poison and toxic scale nothing on their own -- they cost
     * turns and HP rather than stats. They still matter here, because the three
     * abilities below trigger on *any* status, not on a particular one.
     */
    fun statusMultiplier(stat: BattleStat, status: StatusCondition?, abilityName: String?): Float {
        if (status == null) return 1f
        return when (normalise(abilityName)) {
            // Each of these turns a status into an advantage, and takes
            // precedence over the penalty it would otherwise suffer.
            "guts" -> if (stat == BattleStat.ATTACK) 1.5f else penalty(stat, status, burned = false)
            "quickfeet" -> if (stat == BattleStat.SPEED) 1.5f else penalty(stat, status, burned = true)
            "marvelscale" -> if (stat == BattleStat.DEFENSE) 1.5f else penalty(stat, status, burned = true)
            else -> penalty(stat, status, burned = true)
        }
    }

    /** The plain status penalties, with [burned] false where Guts has already cancelled it. */
    private fun penalty(stat: BattleStat, status: StatusCondition, burned: Boolean): Float = when {
        status == StatusCondition.PARALYSIS && stat == BattleStat.SPEED -> 0.5f
        status == StatusCondition.BURN && stat == BattleStat.ATTACK && burned -> 0.5f
        else -> 1f
    }

    /** Stage and status together, which is what a screen wants to display. */
    fun effective(
        base: Int,
        stage: Int?,
        stat: BattleStat,
        status: StatusCondition?,
        abilityName: String?,
        // Truncated rather than rounded, for the same reason as [applyStage]:
        // the game's `speed /= 2` and `(attack * 15) / 10` are integer
        // divisions, so rounding would report a stat the game never has.
    ): Int = (applyStage(base, stage) * statusMultiplier(stat, status, abilityName)).toInt()

    /** Ability names arrive as ROM display text, so compare them loosely. */
    private fun normalise(name: String?): String =
        name?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""
}

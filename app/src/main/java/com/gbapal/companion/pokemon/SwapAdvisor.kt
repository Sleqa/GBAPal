package com.gbapal.companion.pokemon

import com.gbapal.companion.ui.hub.HubMon
import kotlin.math.max

/**
 * Works out which of your Pokemon to send in against the one currently out.
 *
 * This is a *switch-in* tool, and that shapes the whole calculation. Switching
 * is not free: the turn you spend doing it, the opponent attacks, and the
 * Pokemon arriving takes that hit before it has done anything. So a candidate
 * is judged on what happens after eating a hit, not from full health.
 *
 * The hit it eats is predictable. The opponent chooses its move against the
 * Pokemon you currently have out, before it can know what you are bringing in,
 * so the incoming Pokemon is struck by whatever was best against the *outgoing*
 * one. That is what gets modelled here -- not the opponent's best move against
 * the candidate, which it had no way to pick.
 *
 * Beyond that, the question is not "who resists them". Type alone is a poor
 * predictor and the usual reason a switch goes wrong: a super effective move
 * off the wrong attacking stat into the right defending one loses to a neutral
 * move with twice the base power. Every candidate is run through the actual
 * Gen 3 damage formula in both directions and judged on the race that produces
 * -- how many turns each side needs, and who moves first.
 *
 * Live values throughout: current HP rather than maximum, stats already scaled
 * by the opponent's stat stages and by burn or paralysis, and the abilities
 * that invert those penalties. See [BattleStats].
 *
 * What it cannot see is worth stating, because the screen says so too: held
 * items, abilities that change damage, move side effects, and hazards. It is a
 * recommendation, not a reading.
 */
object SwapAdvisor {

    /**
     * The game data a ranking needs, kept as an interface so the maths can be
     * tested without a ROM, an emulator, or a running app behind it.
     */
    interface BattleFacts {
        fun types(speciesId: Int): List<String>
        fun movePower(moveId: Int): Int
        fun moveType(moveId: Int): String
        fun moveCategory(moveId: Int): String
        fun abilityName(abilityId: Int): String

        /** Multiplier for [attackType] hitting a Pokemon of [defenderTypes]. */
        fun effectiveness(attackType: String, defenderTypes: List<String>): Float
    }

    /** One move, costed against a specific target. */
    data class MoveEstimate(
        val moveId: Int,
        val damage: Int,
        val effectiveness: Float,
        val isStab: Boolean,
    )

    /**
     * One of your Pokemon, measured against the opponent.
     *
     * [turnsToKo] and [turnsToFall] are null when that side cannot land a
     * knockout at all -- no damaging move, or every move immune -- which is
     * meaningfully different from "needs a lot of turns" and is why they are
     * not just a large number.
     */
    data class Candidate(
        val mon: HubMon,
        val ourBest: MoveEstimate?,
        val theirBest: MoveEstimate?,
        /**
         * The hit this Pokemon takes on the way in: the opponent's likely
         * choice against whoever is being withdrawn, costed against this
         * Pokemon instead. Null when there is nothing to predict from.
         */
        val switchInHit: MoveEstimate?,
        val turnsToKo: Int?,
        val turnsToFall: Int?,
        val outspeeds: Boolean,
        val ourSpeed: Int,
        val theirSpeed: Int,
    ) {
        /** HP once it has landed, which is where everything else starts from. */
        val hpOnArrival: Int
            get() = mon.currentHp - (switchInHit?.damage ?: 0)

        /** Knocked out by the switch-in hit itself -- the worst possible answer. */
        val faintsOnEntry: Boolean
            get() = hpOnArrival <= 0

        /**
         * Whether this Pokemon wins the straight race, assuming both sides keep
         * using their best move.
         *
         * Moving first is worth a whole turn: at equal turn counts the faster
         * Pokemon lands the last hit before taking it, so equality is a win
         * when you outspeed and a loss when you do not.
         */
        val winsRace: Boolean
            get() {
                if (faintsOnEntry) return false
                val ours = turnsToKo ?: return false
                val theirs = turnsToFall ?: return true
                return if (outspeeds) ours <= theirs else ours < theirs
            }

        /**
         * Hits you take on the way to winning. One fewer when you outspeed,
         * because you land the knockout before their last turn comes round.
         */
        val hitsTaken: Int
            get() {
                val turns = turnsToKo ?: return 0
                return max(0, turns - if (outspeeds) 1 else 0)
            }

        /** HP left once the opponent is down; negative means you go down first. */
        val hpRemaining: Int
            get() = hpOnArrival - hitsTaken * (theirBest?.damage ?: 0)

        /**
         * [hpRemaining] as a share of current HP, and the measure the ranking
         * actually turns on.
         *
         * It is one number that already contains all of it -- both sides'
         * damage, how many turns each needs, who moves first, and how much
         * health the candidate has left to spend. Ranking on turn margin
         * instead prefers a Pokemon that grinds a win out over six turns to one
         * that takes it in two, which is backwards: the fast win costs less
         * health even though its margin is thinner.
         *
         * A candidate that cannot knock the opponent out at all scores below
         * every candidate that can, however sturdy it is -- it is not winning,
         * it is only losing slowly.
         */
        val hpRemainingFraction: Float
            get() {
                if (faintsOnEntry || turnsToKo == null) return CANNOT_WIN
                return hpRemaining.toFloat() / max(1, mon.currentHp)
            }
    }

    /**
     * Every healthy benched Pokemon, best first.
     *
     * [activePlayer] is the one currently out. It is excluded -- switching to
     * the Pokemon already in play is not a move -- and it is also what the
     * opponent's incoming attack is predicted against, since that choice is
     * made before the switch is visible. Null when the active Pokemon cannot be
     * identified, which costs the switch-in prediction but nothing else.
     *
     * [opponentStages] are the opponent's live stat stages, which apply because
     * it is already out. Yours deliberately are not applied even when the
     * Pokemon being replaced has some: stages are lost on a switch, so a
     * candidate coming off the bench arrives at neutral, and scoring it any
     * other way would recommend a boost that is about to be thrown away.
     */
    fun rank(
        party: List<HubMon>,
        opponent: HubMon,
        opponentStages: List<Int>?,
        activePlayer: HubMon?,
        facts: BattleFacts,
    ): List<Candidate> {
        val opponentTypes = facts.types(opponent.speciesId)
        val opponentAbility = facts.abilityName(opponent.abilityId)

        fun opponentStat(base: Int, index: Int, stat: BattleStat) = BattleStats.effective(
            base = base,
            stage = opponentStages?.getOrNull(index),
            stat = stat,
            status = opponent.status,
            abilityName = opponentAbility,
        )

        val theirAttack = opponentStat(opponent.attack, STAGE_ATTACK, BattleStat.ATTACK)
        val theirDefense = opponentStat(opponent.defense, STAGE_DEFENSE, BattleStat.DEFENSE)
        val theirSpeed = opponentStat(opponent.speed, STAGE_SPEED, BattleStat.SPEED)
        val theirSpAttack = opponentStat(opponent.spAttack, STAGE_SP_ATTACK, BattleStat.SP_ATTACK)
        val theirSpDefense = opponentStat(opponent.spDefense, STAGE_SP_DEFENSE, BattleStat.SP_DEFENSE)

        // What the opponent has most likely already locked in: its best damaging
        // move against the Pokemon being withdrawn. Predicted once, against the
        // outgoing Pokemon, then costed separately against each candidate --
        // because it is one chosen move landing on whoever turns up.
        val predictedMoveId = activePlayer?.let { outgoing ->
            val outgoingTypes = facts.types(outgoing.speciesId)
            val outgoingAbility = facts.abilityName(outgoing.abilityId)
            fun outgoingStat(base: Int, stat: BattleStat) =
                BattleStats.effective(base, null, stat, outgoing.status, outgoingAbility)
            bestMove(
                moves = opponent.moves,
                level = opponent.level,
                attackerTypes = opponentTypes,
                physicalAttack = theirAttack,
                specialAttack = theirSpAttack,
                defenderTypes = outgoingTypes,
                physicalDefense = outgoingStat(outgoing.defense, BattleStat.DEFENSE),
                specialDefense = outgoingStat(outgoing.spDefense, BattleStat.SP_DEFENSE),
                facts = facts,
            )?.moveId
        }

        return party
            // A fainted Pokemon cannot be sent in, and the one already out is
            // not a switch.
            .filter { it.currentHp > 0 && it.partySlot != activePlayer?.partySlot }
            .map { mon ->
                val ourTypes = facts.types(mon.speciesId)
                val ourAbility = facts.abilityName(mon.abilityId)
                fun ourStat(base: Int, stat: BattleStat) =
                    BattleStats.effective(base, null, stat, mon.status, ourAbility)

                val ourBest = bestMove(
                    moves = mon.moves,
                    level = mon.level,
                    attackerTypes = ourTypes,
                    physicalAttack = ourStat(mon.attack, BattleStat.ATTACK),
                    specialAttack = ourStat(mon.spAttack, BattleStat.SP_ATTACK),
                    defenderTypes = opponentTypes,
                    physicalDefense = theirDefense,
                    specialDefense = theirSpDefense,
                    facts = facts,
                )
                val theirBest = bestMove(
                    moves = opponent.moves,
                    level = opponent.level,
                    attackerTypes = opponentTypes,
                    physicalAttack = theirAttack,
                    specialAttack = theirSpAttack,
                    defenderTypes = ourTypes,
                    physicalDefense = ourStat(mon.defense, BattleStat.DEFENSE),
                    specialDefense = ourStat(mon.spDefense, BattleStat.SP_DEFENSE),
                    facts = facts,
                )
                val ourSpeed = ourStat(mon.speed, BattleStat.SPEED)

                val switchInHit = predictedMoveId?.let { moveId ->
                    estimate(
                        moveId = moveId,
                        level = opponent.level,
                        attackerTypes = opponentTypes,
                        physicalAttack = theirAttack,
                        specialAttack = theirSpAttack,
                        defenderTypes = ourTypes,
                        physicalDefense = ourStat(mon.defense, BattleStat.DEFENSE),
                        specialDefense = ourStat(mon.spDefense, BattleStat.SP_DEFENSE),
                        facts = facts,
                    )
                }
                val hpOnArrival = mon.currentHp - (switchInHit?.damage ?: 0)

                Candidate(
                    mon = mon,
                    ourBest = ourBest,
                    theirBest = theirBest,
                    switchInHit = switchInHit,
                    turnsToKo = ourBest?.damage?.let { turnsToKo(opponent.currentHp, it) },
                    // Counted from the health it actually arrives with.
                    turnsToFall = theirBest?.damage?.let { turnsToKo(hpOnArrival, it) },
                    outspeeds = ourSpeed > theirSpeed,
                    ourSpeed = ourSpeed,
                    theirSpeed = theirSpeed,
                )
            }
            .sortedWith(
                // Surviving the exchange comes first; everything after it only
                // separates candidates that already agree on that.
                compareByDescending<Candidate> { it.winsRace }
                    .thenByDescending { it.hpRemainingFraction }
                    // Among candidates that come out equally healthy, the one
                    // that ends it soonest gives the opponent fewer turns to do
                    // something this model cannot see.
                    .thenBy { it.turnsToKo ?: Int.MAX_VALUE }
                    .thenByDescending { it.outspeeds },
            )
    }

    /** The hardest-hitting damaging move, or null if there is none that lands. */
    private fun bestMove(
        moves: List<Int>,
        level: Int,
        attackerTypes: List<String>,
        physicalAttack: Int,
        specialAttack: Int,
        defenderTypes: List<String>,
        physicalDefense: Int,
        specialDefense: Int,
        facts: BattleFacts,
    ): MoveEstimate? = moves
        .filter { it != 0 }
        .mapNotNull { moveId ->
            estimate(
                moveId, level, attackerTypes, physicalAttack, specialAttack,
                defenderTypes, physicalDefense, specialDefense, facts,
            )
        }
        .maxByOrNull { it.damage }

    /**
     * One named move costed against one target, or null if it does no damage to
     * it -- a status move, or one the target is immune to.
     */
    private fun estimate(
        moveId: Int,
        level: Int,
        attackerTypes: List<String>,
        physicalAttack: Int,
        specialAttack: Int,
        defenderTypes: List<String>,
        physicalDefense: Int,
        specialDefense: Int,
        facts: BattleFacts,
    ): MoveEstimate? {
        val power = facts.movePower(moveId)
        val category = facts.moveCategory(moveId)
        // Status moves do no damage, so they cannot win or lose the race. They
        // can still decide a matchup in real play -- see the note on what this
        // deliberately does not model.
        if (power <= 0 || category == CATEGORY_STATUS) return null

        val moveType = facts.moveType(moveId)
        val effectiveness = facts.effectiveness(moveType, defenderTypes)
        if (effectiveness == 0f) return null

        val physical = category == CATEGORY_PHYSICAL
        val isStab = moveType in attackerTypes
        return MoveEstimate(
            moveId = moveId,
            damage = damage(
                level = level,
                power = power,
                attack = if (physical) physicalAttack else specialAttack,
                defense = if (physical) physicalDefense else specialDefense,
                stab = isStab,
                effectiveness = effectiveness,
            ),
            effectiveness = effectiveness,
            isStab = isStab,
        )
    }

    /**
     * The Gen 3 damage formula, truncating at each step the way the game does.
     *
     * The random 85-100% roll is left out on purpose. It is the same spread for
     * every candidate, so it cannot change their order, and including it would
     * turn a comparison into a guess -- the numbers shown are what a move does
     * on an average-to-high roll.
     */
    internal fun damage(
        level: Int,
        power: Int,
        attack: Int,
        defense: Int,
        stab: Boolean,
        effectiveness: Float,
    ): Int {
        if (power <= 0 || defense <= 0) return 0
        val base = (2 * level / 5 + 2) * power * attack / max(1, defense) / 50 + 2
        val withStab = if (stab) base * 3 / 2 else base
        return (withStab * effectiveness).toInt()
    }

    /** Hits needed to remove [hp], rounded up -- a leftover point still costs a turn. */
    private fun turnsToKo(hp: Int, perTurn: Int): Int? {
        if (perTurn <= 0) return null
        return (hp + perTurn - 1) / perTurn
    }

    // Stat-stage array order, as gBattleMons stores it.
    private const val STAGE_ATTACK = 0
    private const val STAGE_DEFENSE = 1
    private const val STAGE_SPEED = 2
    private const val STAGE_SP_ATTACK = 3
    private const val STAGE_SP_DEFENSE = 4

    private const val CATEGORY_PHYSICAL = "Physical"
    private const val CATEGORY_STATUS = "Status"

    /** Below any real fraction, so "cannot win" always sorts last. */
    private const val CANNOT_WIN = -1000f
}

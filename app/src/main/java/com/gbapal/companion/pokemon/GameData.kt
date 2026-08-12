package com.gbapal.companion.pokemon

/**
 * The app's single source of Pokemon/move/item facts, wherever they come from.
 *
 * Three tiers, in order:
 *
 * 1. [RomDataReader], driven by the *matched* profile's own table descriptors
 *    -- correct because these addresses were scanned for this exact ROM.
 * 2. [FireRedLiveData], a ROM-agnostic fallback for the CFRU/DPE family that
 *    reads via the ROM's own boot-time header table rather than any profile's
 *    addresses. This is what covers a hack nobody has profiled yet.
 * 3. The bundled JSON, as a last resort when neither of the above can answer.
 *
 * Tier 1 is skipped entirely unless the profile is confirmed to match the
 * loaded ROM ([com.gbapal.companion.memory.MemoryMap.matchesLoadedRom]).
 * Applying a *different* game's addresses doesn't fail cleanly -- it reads
 * real bytes from the wrong place and decodes them as if they meant
 * something, which is how an unregistered hack ended up showing a wall of
 * '?' (Gen3Text's fallback glyph for bytes it can't decode) for names that
 * tier 2 or 3 would have gotten right.
 *
 * The getters are deliberately synchronous so composables can call them
 * directly. Live values are fetched by [prefetch] from the existing party-poll
 * coroutine and cached; a getter never performs I/O, it just misses to the
 * next tier if the prefetch has not reached that id yet.
 */
class GameData(
    private val names: NameTables,
    private val bundledStats: BaseStats,
    private val bundledMoves: MoveData,
    private val reader: RomDataReader?,
    private val typeNames: Map<Int, String>,
    private val liveFallback: FireRedLiveData? = null,
) {
    private val speciesNames = HashMap<Int, String>()
    private val moveNames = HashMap<Int, String>()
    private val itemNames = HashMap<Int, String>()
    private val abilityNames = HashMap<Int, String>()
    private val entries = HashMap<Int, BaseStats.Entry>()
    private val moves = HashMap<Int, MoveData.Entry>()

    /** True when this profile describes enough tables to read the ROM directly. */
    val readsFromRom: Boolean get() = reader != null

    // -- synchronous accessors, safe to call from composition ---------------

    fun speciesName(id: Int): String = speciesNames[id] ?: names.speciesName(id)

    fun moveName(id: Int): String = moveNames[id] ?: names.moveName(id)

    fun itemName(id: Int): String = if (id == 0) "None" else itemNames[id] ?: names.itemName(id)

    fun abilityName(id: Int): String = abilityNames[id] ?: names.abilityName(id)

    fun entry(speciesId: Int): BaseStats.Entry? = entries[speciesId] ?: bundledStats.entry(speciesId)

    fun ppMax(moveId: Int): Int = moves[moveId]?.ppMax ?: bundledMoves.ppMax(moveId)

    fun moveType(moveId: Int): String = moves[moveId]?.type ?: bundledMoves.type(moveId)

    fun moveCategory(moveId: Int): String = moves[moveId]?.category ?: bundledMoves.category(moveId)

    fun movePower(moveId: Int): Int = moves[moveId]?.power ?: bundledMoves.power(moveId)

    fun moveAccuracy(moveId: Int): Int = moves[moveId]?.accuracy ?: bundledMoves.accuracy(moveId)

    fun abilityIdFor(
        speciesId: Int,
        personality: Long,
        hiddenAbilityFlag: Boolean,
        abilityNum: Int? = null,
    ): Int = entry(speciesId)?.abilityId(personality, hiddenAbilityFlag, abilityNum) ?: 0

    // -- prefetch ----------------------------------------------------------

    /**
     * Loads everything the given party needs, so the synchronous getters above
     * have live answers. Cheap to call repeatedly: both live tiers cache, so
     * only ids seen for the first time cost any reads.
     */
    suspend fun prefetch(speciesIds: Collection<Int>, moveIds: Collection<Int>, itemIds: Collection<Int>) {
        for (id in speciesIds.toSet()) {
            if (id <= 0) continue
            loadSpecies(id)
        }
        for (id in moveIds.toSet()) {
            if (id <= 0) continue
            loadMove(id)
        }
        for (id in itemIds.toSet()) {
            if (id <= 0 || itemNames.containsKey(id)) continue
            val name = reader?.text(TABLE_ITEM_NAMES, id) ?: liveFallback?.itemName(id)
            if (name != null) itemNames[id] = name
        }
    }

    private suspend fun loadSpecies(id: Int) {
        if (!speciesNames.containsKey(id)) {
            val name = reader?.text(TABLE_SPECIES_NAMES, id) ?: liveFallback?.speciesName(id)
            if (name != null) speciesNames[id] = name
        }
        if (entries.containsKey(id)) return

        val entry = readerSpecies(id) ?: liveFallback?.baseStats(id) ?: return
        entries[id] = entry

        for (abilityId in listOf(entry.ability1, entry.ability2, entry.hiddenAbility)) {
            if (abilityId > 0 && !abilityNames.containsKey(abilityId)) {
                val name = reader?.text(TABLE_ABILITY_NAMES, abilityId) ?: liveFallback?.abilityName(abilityId)
                if (name != null) abilityNames[abilityId] = name
            }
        }
    }

    private suspend fun readerSpecies(id: Int): BaseStats.Entry? {
        val reader = reader ?: return null
        val record = reader.record(TABLE_SPECIES_STATS, id) ?: return null
        fun field(name: String) = reader.field(TABLE_SPECIES_STATS, record, name)
        val hp = field("hp") ?: return null
        val type1 = field("type1")?.let { typeNames[it] }
        val type2 = field("type2")?.let { typeNames[it] }

        val base = BaseStats.Entry(
            hp = hp,
            attack = field("attack") ?: 0,
            defense = field("defense") ?: 0,
            spAttack = field("spAttack") ?: 0,
            spDefense = field("spDefense") ?: 0,
            speed = field("speed") ?: 0,
            type1 = type1,
            type2 = type2.takeIf { it != type1 },
            ability1 = field("ability1") ?: 0,
            ability2 = field("ability2") ?: 0,
            hiddenAbility = field("hiddenAbility") ?: 0,
        )
        return applyRevisedProfile(reader, id, base)
    }

    /**
     * Layers Rogue's "Revision Mode" rebalance table over the normal species
     * entry, when a profile describes one. Most species are not rebalanced,
     * which the table marks with sentinel values (TYPE_NONE / ABILITY_NONE)
     * rather than omitting the record, so only non-sentinel fields replace the
     * base value -- everything else falls through untouched.
     */
    private suspend fun applyRevisedProfile(reader: RomDataReader, id: Int, base: BaseStats.Entry): BaseStats.Entry {
        val record = reader.record(TABLE_SPECIES_REVISED, id) ?: return base
        fun field(name: String) = reader.field(TABLE_SPECIES_REVISED, record, name)
        val type1 = field("type1")?.takeIf { it != TYPE_NONE_SENTINEL }?.let { typeNames[it] }
        val type2 = field("type2")?.takeIf { it != TYPE_NONE_SENTINEL }?.let { typeNames[it] }
        val ability1 = field("ability1")?.takeIf { it != ABILITY_NONE_SENTINEL }
        val ability2 = field("ability2")?.takeIf { it != ABILITY_NONE_SENTINEL }
        val hiddenAbility = field("hiddenAbility")?.takeIf { it != ABILITY_NONE_SENTINEL }

        return base.copy(
            type1 = type1 ?: base.type1,
            type2 = type2 ?: base.type2,
            ability1 = ability1 ?: base.ability1,
            ability2 = ability2 ?: base.ability2,
            hiddenAbility = hiddenAbility ?: base.hiddenAbility,
        )
    }

    private suspend fun loadMove(id: Int) {
        if (!moveNames.containsKey(id)) {
            val name = reader?.text(TABLE_MOVE_NAMES, id) ?: liveFallback?.moveName(id)
            if (name != null) moveNames[id] = name
        }
        if (moves.containsKey(id)) return

        val entry = readerMove(id) ?: liveFallback?.moveData(id) ?: return
        moves[id] = entry
    }

    private suspend fun readerMove(id: Int): MoveData.Entry? {
        val reader = reader ?: return null
        val record = reader.record(TABLE_MOVE_DATA, id) ?: return null
        fun field(name: String) = reader.field(TABLE_MOVE_DATA, record, name)
        val power = field("power") ?: return null
        val type = field("type")?.let { typeNames[it] }
        val category = field("category")
            ?.let { CATEGORY_NAMES.getOrNull(it) }
            ?: derivedCategory(power, type)

        return MoveData.Entry(
            power = power,
            accuracy = field("accuracy") ?: 0,
            ppMax = field("pp") ?: 0,
            type = type,
            category = category,
        )
    }

    /**
     * Category for games that predate the physical/special split, where it
     * followed from the move's type. Keyed on the type *name* rather than its
     * numeric id, because the ids are renumbered freely between hacks.
     */
    private fun derivedCategory(power: Int, type: String?): String = when {
        power == 0 -> "Status"
        type in PHYSICAL_TYPES -> "Physical"
        else -> "Special"
    }

    companion object {
        const val TABLE_SPECIES_NAMES = "speciesNames"
        const val TABLE_SPECIES_STATS = "speciesStats"
        const val TABLE_SPECIES_REVISED = "speciesRevisedProfile"
        const val TABLE_MOVE_NAMES = "moveNames"
        const val TABLE_MOVE_DATA = "moveData"
        const val TABLE_ABILITY_NAMES = "abilityNames"
        const val TABLE_ITEM_NAMES = "itemNames"

        private const val TYPE_NONE_SENTINEL = 0xFF
        private const val ABILITY_NONE_SENTINEL = 0

        private val CATEGORY_NAMES = listOf("Physical", "Special", "Status")

        private val PHYSICAL_TYPES = setOf(
            "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel",
        )
    }
}

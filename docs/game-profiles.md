# Adding a game

A game profile is one JSON file describing **where** a ROM keeps its data. The
app reads that data live from the loaded ROM, so a profile costs a few kilobytes
rather than the ~440KB a bundled copy of one game's Pokemon data used to.

Nothing about the app is specialised to a particular ROM base. A profile that
gives correct addresses and field offsets works whether the game is built on
FireRed, Emerald, Ruby, a decomp, or something nobody here has seen — the only
real requirement is that its tables are **fixed-stride records**.

The [gbamap](https://github.com/Sleqa/GBAPal) helper tool generates most of this
automatically; drag a `.gba` onto its `ScanROM.bat` and it writes a profile.

## Where it goes

Drop the file in one of two places. There is nothing to register — the app
discovers profiles by listing these folders, so no index, no shared file, and
no app code mentions your game.

| Location | Use it for |
|---|---|
| `app/src/main/assets/game_profiles/<your-game>.json` | Shipping a game with the app. |
| `Android/data/com.gbapal.companion/files/game_profiles/<your-game>.json` on the device | Testing without rebuilding. |

A drop-in file on the device wins over a bundled profile with the same `id`, so
you can iterate on your own game — or correct a wrong address in a shipped one —
without touching any other game. A profile that fails to parse is skipped with a
warning in logcat and everything else keeps working, so a half-written file can
never take the app down.

## The profile

### Required

Genuinely all of it:

```json
{
  "displayName": "Pokemon Oddesy",
  "crc32s": ["1AFF85B4"],
  "party":      { "firstSlotAddress": "0x02024284", "slotStride": 100, "slotCount": 6 },
  "enemyParty": { "firstSlotAddress": "0x0202402C", "slotStride": 100, "slotCount": 6 }
}
```

| Key | Meaning |
|---|---|
| `displayName` | Shown once the ROM is recognised. |
| `crc32s` | CRC32 of each ROM build this describes, which `gbamap identify` prints. Version-exact: a new patch is a new CRC32, added to this list. |
| `party` / `enemyParty` | Where the six 100-byte party structs start. |

`id` defaults to the filename, so `oddesy.json` needs no `id`. Everything below
is optional — anything you leave out is simply a feature the app skips for your
game, never a wrong guess.

Set `"isDefault": true` if your profile should be the one used before any ROM is
identified. Exactly one bundled profile should have it.

### Party layouts

```json
"party": {
  "name": "gPlayerParty",
  "firstSlotAddress": "0x020375F8",
  "slotStride": 100,
  "slotCount": 6,
  "confidence": "verified"
}
```

Party structs are read in either of the two Gen 3 layouts — plaintext
(CFRU-style) or standard encrypted-and-shuffled — detected per slot from the
struct's own checksum. No configuration needed.

### `dataTables`

Each entry says where a table is and how to read one record from it.

```json
"dataTables": {
  "speciesNames": {
    "address": "0x08D5D9D8", "stride": 260, "count": 1537,
    "text": { "via": "inline", "offset": "0x2C", "length": 12 }
  },
  "speciesStats": {
    "address": "0x08D5D9D8", "stride": 260, "count": 1537,
    "layout": "expansion-species",
    "fields": { "ability1": { "offset": "0x18", "size": 2 } }
  }
}
```

Recognised table names: `speciesNames`, `speciesStats`, `moveNames`, `moveData`,
`abilityNames`, `itemNames`. Anything absent falls back to the shared bundled
tables, which are only correct for games sharing vanilla's numbering.

**`text`** — how to get a name out of a record. `via: "inline"` reads characters
from within the record; `via: "pointer"` follows a 4-byte ROM pointer at
`offset` into a shared string pool, which is how decomp-based games store names.

**`layout`** — names a preset set of field offsets (see `RecordLayouts.kt`):
`gen3-species`, `expansion-species`, `gen3-move`, `gen3-move-split`.

**`fields`** — declares or overrides individual fields. Two addressing modes:

```json
"power":  { "offset": "0x01", "size": 1 }   // byte-aligned, little-endian
"power":  { "bit": 87, "width": 8 }         // bit-packed
```

A layout is only a convenience. **Omit `layout` entirely and declare every field
yourself** and an engine with no preset works with no app changes — which is the
point of the format. Field names the app looks for:

- species: `hp` `attack` `defense` `speed` `spAttack` `spDefense` `type1`
  `type2` `ability1` `ability2` `hiddenAbility`
- moves: `power` `accuracy` `pp` `type` `category`

Any field the game doesn't store can be left out. A missing `category` is
derived from the move's type, the pre-Gen-4 rule.

### `typeNames`

```json
"typeNames": { "13": "Grass", "4": "Poison" }
```

Required per game, because forks renumber the type enum: inserting one type
shifts every id after it. Three real hacks here use three different ids for
Fairy (18, 19 and 23). A shared list would mislabel every type in the game.

### `anchors`

Named one-off values, looked up by name. Absent anchors disable the feature that
uses them rather than guessing an address.

| Name | Drives |
|---|---|
| `battleActiveFlag` | Auto-opening the opponent view and returning to the hub. Nonzero while in battle. Preferred over the two below. |
| `totalBattleCounter` | Battle *start* only, by noticing the value change. Fallback. |
| `repelStepCount` | The infinite-repel toggle. **Written to** — never guess this one. |

### Optional

`overworldObjects` (used to detect the player moving, as a battle-end fallback),
`scriptVars`, `frontSpriteTable` / `paletteTable`, `engine`, `version`.

Sprite tables use `pointerOffset` for engines that keep sprite pointers inside a
larger per-species struct instead of a dedicated 8-byte-stride table:

```json
"frontSpriteTable": {
  "firstSlotAddress": "0x08D5D9D8", "slotStride": 260,
  "pointerOffset": "0x58", "confidence": "verified"
}
```

## Confidence

Every address carries a `confidence`. Behavioural values — battle flags, step
counters, live coordinates — cannot be found by analysing a ROM; they only
reveal themselves by watching memory change while playing, which is what
`gbamap anchors` is for. Mark those `verified` only after confirming them
against a running game, and leave them out otherwise.

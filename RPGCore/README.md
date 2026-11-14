# RPGCore

The core of the server: classes, stats, leveling, combat, custom mobs, custom blocks and items, the wallet,
and the resource pack. Other plugins build on it (see `../AGENTS.md`).

## Code map (`src/main/java/dev/jason/rpgcore/`)

| Class | What it does |
|---|---|
| `RPGCore` | Plugin entry: creates the managers, schedules tasks, `/rpg reload` |
| `Stat`, `RpgClass`, `ClassManager` | Stat list + caps; a class's stat at a level; loads/saves `classes.yml` |
| `PlayerManager` | Class, level, XP, mana on the player; RPG health scale; stat = class + item stats |
| `CombatListener` | The damage pipeline (attack, crit, defense/pierce, damage reduction, life steal, slowness, poison), XP on kills, mob health bars |
| `MobManager` | Custom mobs from `mobs.yml` (+ registered by plugins), mob levels, spawning, name-tag health bars |
| `SpawnZoneManager` | `/rpg zone`: circles that keep custom mobs alive while players are near |
| `CheckpointManager` | Class markers: walk through once to get a class |
| `CustomBlockManager` | Custom blocks on note block states (see AGENTS.md), placing/breaking/mining speed/drops |
| `CustomItemManager` | Custom items from `items.yml` (+ registered by plugins), recipes, item stats, vanilla-use blocking |
| `ItemStats` | Stats on items (`rpgcore:stat_<key>`), tooltip lines |
| `Wallet` | Copper/ambrosia balances + Copper Coin / Ambrosia items, `/wallet` |
| `PackHost` | Builds the final resource pack (base zip + custom blocks/items) and serves it on port 8163 |
| `StatsMenu` | The `/stats` spellbook (Paper dialog + font tricks) |
| `RpgCommand` | `/rpg` commands |

## Config files (`plugins/RPGCore/`)

- `config.yml` - leveling curve (max 100, late curve from level 25), mana regen, mob levels, resource pack host/port, spawn blocking
- `classes.yml` - 8 classes, `base` + `per-level` for every stat
- `mobs.yml` - custom enemies (type, name, health, damage, defense, speed, scale, xp, equipment)
- `zones.yml`, `checkpoints.yml` - made in game with `/rpg zone` and `/rpg checkpoint`
- `blocks.yml` + `textures/block/` - custom blocks; `block-states.yml` is written by the plugin (don't edit)
- `items.yml` + `textures/item/` - custom items with optional `stats:` and `recipe:`

## Commands

`/stats`, `/wallet [deposit | withdraw <copper|ambrosia> <n>]`, and for ops `/rpg class|player|mob|zone|checkpoint|block|item|pack|reload`
(`/rpg help` lists them). `/wallet give <player> <currency> <n>` for testing.

## Resource pack

`tools/make_pack.py` (needs Pillow) draws the stats book, fonts and icons and writes `resourcepack/` +
`RPGCore-pack.zip`; copy the zip to `plugins/RPGCore/`. Custom block/item textures are added by the plugin at
runtime, no need to rebuild the zip for those. Texture scripts: `make_black_cobble.py`, `make_black_stone_tools.py`.

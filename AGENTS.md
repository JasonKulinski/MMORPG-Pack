# Notes for AI assistants (and humans) working in this repo

Read this first. It explains how the server and the plugins fit together, the conventions to follow,
and the gotchas that already cost us time.

## The server

- **Paper 26.3 (build 200)**, Minecraft Java 26.3, **Java 25**. WorldEdit 7.4.6.
- Runs on Jason's Windows PC (`Documents\minecraft-server`), started with `start.bat`. Friends join on the LAN.
- `server.properties`: `difficulty=easy` (custom mobs need a non-peaceful difficulty; RPGCore blocks vanilla hostile mobs).
- `config/paper-global.yml`: `block-updates.disable-noteblock-updates: true` (**required** by custom blocks).
- One server resource pack, built and hosted by RPGCore (see "Resource pack" below).

## The plugins (one folder each)

| Plugin | Depends on | Owns |
|---|---|---|
| `RPGCore/` | - | Classes, stats, leveling, health/mana, combat math, custom mobs (+ levels), spawn zones, class checkpoints, custom blocks, custom items + item stats, wallet (copper/ambrosia), the resource pack, the `/stats` book |
| `RPGWeapons/` | RPGCore | **Weapons. Owned by the other developer - don't change it unless asked.** |
| `RPGTowns/` | WorldEdit, RPGCore | Towns, Alderman + vote threshold, plots, the Planner NPC, player designs (empty design world, voting, pasting), the Treasurer NPC and town treasury |
| `RPGExplore/` | RPGCore, RPGTowns | Everything outside towns: level bands, wild enemies, coin/ambrosia drops, wandering traders, side quests, pixie areas + red quartz, territories, dungeons, bosses |

`server-config/` mirrors the server's config files (RPGCore yml files + textures, WorldEdit config, the
`zone_skies` datapack). `docs/` has the WorldEdit cheat sheet and world-building notes.

### Rules of thumb

- **RPGCore owns the damage math** (`CombatListener`). Other plugins give items stats (`ItemStats`) or add
  abilities; they don't change damage themselves.
- **Register content through RPGCore** instead of re-implementing it:
  `rpg.items().register(...)` (item + texture in the pack + stats), `rpg.mobs().register(MobDef)`,
  `rpg.mobs().spawn(def, location, level)`, `rpg.wallet()`, `rpg.players().addXp/spendMana/stat`,
  then `rpg.packHost().load()` once after registering textures.
- Keep things data-driven: content goes in yml files with a comment header explaining every field.
- Match the existing style: small classes per feature, comments that explain *why*, MiniMessage for text,
  Paper dialogs (`player.showDialog`) for menus, PersistentDataContainer for tags on items/entities/players.
- Jason prefers minimal, direct solutions that mirror existing patterns. No speculative abstractions.

## Important mechanics (read before touching combat, items or blocks)

- **Health is on its own scale.** Minecraft caps health at 1024, classes go into the thousands. A player's
  real heart bar stays at 20 and shows the *percentage* of RPG health (`PlayerManager.maxHealth/health`).
  Damage is computed on the RPG scale and converted (`CombatListener`). Mobs: real health is
  `min(rpgHealth, 1024)`, scaled the same way. Environmental damage (falling, lava) is therefore a percentage.
- **Stats** = class value at level (`base + perLevel * (level-1)`, capped per stat) **+ item stats** from main hand,
  off hand and armor (`rpgcore:stat_<key>` doubles on the item). Caps: damage_reduction 50, pierce 60,
  crit_chance 75, life_steal 25, slowness 40, poison 40, speed 50.
- **Leveling**: max 100. XP to next level = `100 * level^1.5`, from level 25 on the curve continues with
  exponent 2.5. Only custom mobs give XP.
- **Mob levels**: level 1 = mobs.yml stats; each level adds 15% health/damage/defense and 25% XP
  (`mob-levels` in RPGCore config). Level is stored on the entity (`rpgcore:mob_level`); `mobs().of(entity)`
  returns the leveled stats.
- **Custom blocks are note block states.** A custom block = one (note, powered) combination; the resource pack
  maps that combination to a texture *for every instrument*, because players' clients change a note block's
  instrument on their own when neighbours change (they think it's a vanilla note block). That's why there are
  49 slots, not ~1150. The block *item* is paper underneath (so the client never predicts a vanilla note block
  placement) and RPGCore places the block itself. `block-states.yml` records which combination each block uses:
  **never edit it by hand**, it would change blocks already placed in the world. WorldEdit name of a block:
  `/rpg block list`.
- **Custom items are vanilla items underneath** (paper, potion, stone_pickaxe...) with `item_model` pointing at
  our texture and a `rpgcore:item` tag. Non-durable custom items (coins, ambrosia, potions, materials) are
  blocked from vanilla recipes, workstations and villager trades; recipes added by plugins (namespace not
  `minecraft`) still work.
- **Wallet**: copper and ambrosia are balances on the player (`rpgcore:wallet_copper` etc.) *and* physical
  items (Copper Coin, Ambrosia). They are never mined or crafted - only dropped by mobs, quests, dungeons, bosses.
  `/wallet deposit|withdraw`, or the Treasurer NPC.
- **Resource pack**: `RPGCore/tools/make_pack.py` builds the base pack (stats book art, fonts, icons) into
  `RPGCore-pack.zip`. The plugin adds custom block/item files at runtime and hosts the result on port 8163.
  `resource-pack.host: auto` = players download from the address they joined with. Changing textures needs a
  `/rpg reload` and players accept the new pack.
- **Stats book** (`StatsMenu`): one line of text in a Paper dialog, positioned with negative-space font
  characters. Coordinates must match `make_pack.py`. Font glyphs can't be wider than 256 px (the book image is
  split into two halves for that reason).

## Building

Gradle files exist in each plugin folder but haven't been verified. What has been used so far is plain `javac`
against the Paper API jar and its dependencies, e.g.:

```
javac --release 25 -cp "lib/*" -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/ && (cd build/classes && jar cf ../RPGCore.jar .)
```

Plugins that depend on others add those jars to the classpath (RPGTowns also needs the WorldEdit jar).
Build order: RPGCore, then RPGWeapons / RPGTowns, then RPGExplore.

## Deploying

- RPGCore is installed as `plugins/RPGCore-0.1.0.jar` (old file name kept). To update a running server, drop the
  new jar into `plugins/update/` **with the same file name**; Paper swaps it in on the next restart.
- `RPGTowns.jar`, `RPGWeapons.jar`, `RPGExplore.jar` live directly in `plugins/`.
- Always restart after a jar change (no plugin reloaders).

## Testing

A throwaway Paper 26.3 server (offline mode) with the plugins + WorldEdit is the fastest check that a plugin
enables and its yml loads. Things that need a real player (menus, placing blocks, combat feel) must be tried in
game. To script checks against package-private code, build a test copy of the plugin whose main class extends
the real one (a separate plugin jar can't access package-private members: different class loader).

## Where data lives on the server (`plugins/<Plugin>/`)

- RPGCore: `classes.yml`, `mobs.yml`, `zones.yml`, `checkpoints.yml`, `blocks.yml`, `block-states.yml`, `items.yml`, `config.yml`, `textures/`, `RPGCore-pack.zip`
- RPGTowns: `towns.yml` (towns, plots, centers, treasuries), `designs.yml`, `schematics/<id>.schem`, `players/` (saved inventories while in the design world), `config.yml`; world `rpgtowns_design`
- RPGExplore: `config.yml` (all tuning), `explore.yml` (areas, territories, dungeons, boss arenas), `textures/`
- Player data (class, level, XP, mana, wallet) is on the player (PersistentDataContainer), no database.

## Not built yet (ideas from the design board)

Raids between towns (towns lose shared resources, rebuild from saved designs, paid from the treasury),
building costs for town designs, the real reward for Hendor (RPGWeapons), professions/crafting, story quests.

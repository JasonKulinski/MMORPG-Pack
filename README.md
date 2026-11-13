# Minecraft MMO Server

A Paper 26.3 server with an MMO RPG theme: classes, leveling, custom mobs, custom blocks and items, and themed zones with custom skies.

## What's in here

| Folder | What it is |
|---|---|
| `RPGCore/` | The custom plugin (Java). Classes, stats, leveling, the stats book, custom mobs, spawn zones, custom blocks and items, and the resource pack host. |
| `RPGWeapons/` | Weapons plugin (starter): weapons with stats and mana abilities, defined in `weapons.yml`. Depends on RPGCore. |
| `RPGCore/tools/` | Python scripts that draw the textures and build the resource pack (`make_pack.py`). |
| `server-config/` | The server's config files: RPGCore settings (`classes.yml`, `mobs.yml`, `blocks.yml`, `items.yml`, `zones.yml`...), WorldEdit config, the `zone_skies` datapack (custom biomes) and `start.bat`. |
| `docs/` | WorldEdit cheat sheet with all the terraforming brushes used for each zone. |

The world itself, server jars and logs are not in the repo.

## Setting up a server

1. Install **Java 25** (Temurin).
2. Make a server folder with **Paper 26.3** (`paper-26.3-*.jar` from papermc.io) and **WorldEdit 7.4.6** in `plugins/`.
3. Copy everything from `server-config/` into the server folder (keep the folder structure).
4. In `config/paper-global.yml`, set:
   ```yaml
   block-updates:
     disable-noteblock-updates: true
   ```
   Custom blocks need this.
5. In `server.properties`: `difficulty=easy` (custom mobs need a non-peaceful difficulty; vanilla hostile mobs are blocked by RPGCore).
6. Build the plugin (below), put `RPGCore.jar` in `plugins/`, and copy `RPGCore/RPGCore-pack.zip` into `plugins/RPGCore/`.
7. Run `start.bat`. Players are prompted to download the resource pack when they join.

## Building the plugin

Needs Java 25 and the Paper API (`paper-api-26.3.build.200-stable`).

```
cd RPGCore
gradle build
cd ../RPGWeapons
gradle build        # needs RPGCore built first
```

The resource pack:

```
cd RPGCore
python tools/make_pack.py
```

This writes `resourcepack/` and `RPGCore-pack.zip`. Custom block and item textures in `plugins/RPGCore/textures/` are added to the pack automatically by the plugin.

## Writing a plugin on top of RPGCore (e.g. RPGWeapons)

Add `depend: [RPGCore]` to your `plugin.yml` and RPGCore's jar as a `compileOnly` dependency (see `RPGWeapons/build.gradle.kts`). Then:

```java
RPGCore rpg = (RPGCore) Bukkit.getPluginManager().getPlugin("RPGCore");

// Add an item: RPGCore makes it, adds its texture to the resource pack and puts the stats on it.
rpg.items().register("ember_blade", "<#ff7a2e>Ember Blade", Material.IRON_SWORD,
        new File(getDataFolder(), "textures/ember_blade.png"), Map.of(Stat.ATTACK, 60.0));
rpg.packHost().load();                          // after registering, rebuild the resource pack

ItemStack sword = rpg.items().item(rpg.items().get("ember_blade"), 1);
CustomItem held = rpg.items().of(player.getInventory().getItemInMainHand());   // which custom item, or null
rpg.players().spendMana(player, 30);            // false if not enough mana
rpg.players().stat(player, Stat.ATTACK);        // class + held item + armor, capped
```

Item stats: anything held in the main hand or worn as armor adds its stats to the player's class stats (stored on the item as `rpgcore:stat_<name>`; `ItemStats.apply(item, stats)` writes them and the tooltip). RPGCore's own `items.yml` items can have a `stats:` section too.

Rule of thumb: RPGCore owns the damage math. Other plugins give items stats and add abilities, rather than changing damage themselves.

## Useful commands (in game, as op)

| Command | What it does |
|---|---|
| `/stats` | Open the stats book |
| `/rpg class list` / `info <id>` / `set <id> <stat> <base> [perLevel]` | View and edit classes |
| `/rpg player setclass <player> <class>` | Give a player a class |
| `/rpg mob spawn <id> [count]` | Spawn a custom mob |
| `/rpg zone create <id> <radius> <max> <mob[:weight]>...` | Make a spawn zone where you stand |
| `/rpg checkpoint create <id> <class> [radius]` | Class-choosing marker |
| `/rpg block list` / `give <id>` | Custom blocks (list shows the WorldEdit name) |
| `/rpg item list` / `give <id>` | Custom items and tools |
| `/weapons list` / `give <id> [player]` | RPGWeapons weapons |
| `/rpg reload` | Reload all config files and resend the resource pack |

## Working together

- Make a branch for each change: `git checkout -b my-change`
- Push it and open a pull request on GitHub.
- Don't edit `server-config/plugins/RPGCore/block-states.yml` by hand: it records which note block state each custom block uses, and changing it changes blocks already placed in the world.

# RPGExplore

The world outside towns - the "Exploring" part of the design board. Needs RPGCore and RPGTowns.
All tuning is in `plugins/RPGExplore/config.yml` (every setting is commented); places made in game are in `explore.yml`.

## Features

| Feature | How it works | Setup |
|---|---|---|
| **Level bands** | Enemy level depends on distance to the nearest town center: 0-499 blocks = levels 1-5, 500-999 = 6-10, ... A safe circle around each town has no wild spawns. A title tells players when they cross into a new band. | `/towns center <town>`; `level-bands` in config |
| **Wild enemies** | Spawn around players in the wilds, themed by the nearest town (`themes`, e.g. ice zombies near the ice town), each mob capped at a max level. Removed when nobody is near. | `themes`, `mobs`, `wild-spawns` |
| **Loot** | Every custom enemy drops copper coins (more at higher levels) and sometimes ambrosia. Never mined. | `drops` |
| **Wandering traders** | Sometimes appear near players in the wilds; sell for copper coins (10 copper = small health potion). Despawn after 5 minutes. | `traders` |
| **Potions** | Small health potion heals 25%, healing potion 60%. Healing potion = red quartz crystal + glass bottle. | `potions` |
| **Side quests** | A Lost Traveler appears near a player: fetch quests (bring items for copper + XP) or the lost ring (find it in a chest under a light beam, return it for a reward, or keep it: +5% crit, +40 health in the off hand). | `quests` |
| **Evil pixie areas** | Circular areas with an entry title; only evil pixies spawn there; red quartz crystals (custom block) grow there. | `/explore area create <id> pixie <radius> <name>`, `/explore area scatter <id> <n>` |
| **Territories** | Claimable resource sites. A town captures one by standing in the circle uncontested for 60 s; the owner gets e.g. 30 copper/hour in its treasury. Others can take it back. | `/explore territory create <id> <resource> <per-hour> <name>` |
| **Dungeons** | Walk in and waves of custom enemies spawn; clearing the last wave rewards everyone who took part; then a cooldown. | `/explore dungeon create <id> <radius> <level>`, waves in `explore.yml` |
| **Bosses** | One-of-one boss (Hendor, a level 10 piglin brute) with a boss bar and a knock-back slam. Top damage dealer gets the drop; the first winner gets a Legendary tier, every later winner one tier lower. | `/explore boss set hendor` at the arena |

## For the RPGWeapons team: Hendor's drop

The boss drops a placeholder `hendors_token` tagged with `rpgexplore:boss` = `hendor`, `rpgexplore:tier`
(0 = Legendary ... 4 = Common) and `rpgexplore:edition` (1 = first winner). Turn it into Hendor's Weighted Blade
(the design board: pushes entities back when activated; weaker for later tiers), e.g. by listening for
`EntityPickupItemEvent` or by changing `bosses.hendor.drop` in config to your item id.

## Code map

| Class | What it does |
|---|---|
| `RPGExplore` | Entry: registers mobs/items into RPGCore, schedules everything |
| `Wilds` | Level bands, wild spawning/despawning, band titles, coin/ambrosia drops |
| `Traders` | Wandering traders, health potions |
| `Quests` | Lost Traveler NPCs, fetch and ring quests |
| `Regions` | Pixie areas and territories (capture, boss bars, income) |
| `Dungeons` | Wave dungeons |
| `Bosses` | Boss arenas, slam, boss bar, tiered drop |
| `Commands` | `/explore` |
| `Util` | Ground finding, giving items, dialog menus |

Test helpers (ops): `/explore trader`, `/explore quest`, `/explore wild`, `/explore level`, `/explore list`.
`tools/make_textures.py` draws the coin, ambrosia, potion, ring, token and red quartz textures.

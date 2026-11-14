# World-building notes (WorldEdit + plugin layout)

Practical notes for building the world so the plugins work well. The full brush list per zone is in
`WorldEdit-Cheat-Sheet.html`.

## Laying out the map

- **8 towns**, each with a theme matching a class zone (Life, Knight, Tank, Swift/Agility, Fire, Arcane, Death, Ice).
- Danger rises every **500 blocks** from the *nearest* town center (levels 1-5, 6-10, 11-15...). Plan the map in
  rings: easy farmland and forests within ~500 blocks of a town, harder biomes further out, the hardest content
  (dungeons, bosses) where rings from different towns meet or far from all towns.
- Towns closer than ~1000 blocks to each other overlap their easy rings. Spread them out if you want real
  wilderness in between.
- Each town gets a **safe circle** (120 blocks by default) with no wild spawns. Build the town inside it.
- Set the town middle with `/towns center <town>` (placing the first NPC sets it automatically).
- Put each town's themed enemies in RPGExplore's `themes` (e.g. `icevale: {ice_zombie: 40}`). New enemy types go in
  RPGExplore's `mobs` list or RPGCore's `mobs.yml`.

## Towns

- Plots are square: houses 6x6 (12 tall), 8x8 (16), 10x10 (20); town buildings 16x16 (32), 32x32 (48).
  Lay out streets on that grid; leave 1-2 blocks between plots for paths and fences.
- `/towns plot create <town> <plot> <size>` puts the plot's corner **at your feet** and extends toward **+X/+Z**
  (east/south). Stand on the ground level the building should start at. `/towns plot show <town>` draws them.
- Pasting a design clears the whole plot volume first - don't put decorations inside plot boundaries.
- Place the Planner and Treasurer NPCs in a central square: `/towns npc <town>` and `/towns npc <town> treasurer`.

## Exploring content

- **Evil pixie hollow**: build the prefab first (dark, cramped, glowing - deepslate, crying obsidian, amethyst,
  magenta/purple glass, cobwebs), save it with `//copy` + `//schem save pixie_hollow`, paste copies with `//paste -a`.
  Then stand in the middle: `/explore area create <id> pixie <radius> Evil Pixie Hollow` and
  `/explore area scatter <id> 20` to grow red quartz crystals on the ground.
- **Territories**: build something worth fighting over (a copper mine entrance, lumber camp, quarry) and put the
  capture circle (radius 6) in an open, defensible spot: `/explore territory create <id> copper 30 Copper Mine`.
- **Dungeons**: a closed space (crypt, cave) about 20-30 blocks across, flat enough that mobs can spawn
  (they spawn within 8 blocks up/down of where you stood). Stand in the middle: `/explore dungeon create <id> 12 <level>`.
  Waves are edited in `plugins/RPGExplore/explore.yml` (`"ghoul:5,bone_archer:3"`), then restart.
- **Boss arena** (Hendor): an open, round arena ~30 blocks across with walls, so the knock-back slam has room
  and players can't kite the boss out (it's pulled back past 40 blocks). Stand in the middle: `/explore boss set hendor`.
- **Spawn zones** (RPGCore, for fixed enemy camps inside or near towns): `/rpg zone create <id> <radius> <max> <mob[:weight]>...`.

## Custom blocks in WorldEdit

- Custom blocks are note block states. Use the exact name from `/rpg block list`, e.g. black cobblestone =
  `note_block[instrument=basedrum,note=0,powered=false]` (any instrument with note 0, unpowered looks the same).
- They work in masks and mixes: `//brush sphere 50%note_block[instrument=basedrum,note=0,powered=false],50%deepslate 3`.
- Red quartz crystal is another custom block; check `/rpg block list` for its name.
- Plain note blocks you place by hand are forced to one look and can't be tuned (so they never turn into a custom block).

## Biomes and skies

- Custom biomes live in the `zone_skies` datapack (`world/datapacks/zone_skies/data/myserver/worldgen/biome/`):
  `myserver:fire_zone` (orange sky, ash), `myserver:death_zone` (black sky, purple water),
  `myserver:arcane_zone` (dark purple sky and fog, purple sparkles). New biomes need a restart.
- Paint them with `//brush biome -c sphere 7 myserver:arcane_zone` (`-c` = whole column, so the sky changes) or
  `//setbiome` on a selection. Rejoin to see the change. All three have no natural mob spawns.

## WorldEdit habits that saved us

- Set the **brush first, then the mask** - re-binding a brush clears its mask. Resize with `/size`.
- `//gmask air` keeps decoration brushes from overwriting blocks; turn it off with `//gmask` afterwards or later
  brushes paint nothing.
- `>block` masks place on top of a block (`/mask >grass_block`). Don't include dirt in ground masks.
- `//paste -a` skips air (keeps water and terrain around the paste).
- Forest brushes use Minecraft feature names (`oak`, `dark_oak`, `azalea_tree`), not `trees_*`.
- Max brush radius was raised to 100 in `plugins/WorldEdit/config.yml`.
- 70/20/10 palettes (one main block, one secondary, one accent) read best, e.g. Arcane ground
  `70%deepslate_tiles,20%purpur_block,10%mycelium`, spikes `70%purple_concrete,20%amethyst_block,10%crying_obsidian`.

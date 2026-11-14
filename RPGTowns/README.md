# RPGTowns

Towns built from player designs. Needs WorldEdit and RPGCore.

## How it plays

Each town has a **Planner** NPC (and optionally a **Treasurer**), both mannequins.

- **Planner**: join the town (one per player), design a building, vote on designs, build on a plot, see your designs.
  The Alderman also sets the approval threshold (1-5).
- **Designing**: pick a plot size, get sent to the empty world `rpgtowns_design` with a grass floor that size and a
  per-player border. Creative mode; the real inventory is saved to `players/<uuid>.yml` and restored on leave,
  logout or teleport. The nether star menu submits the design (saved with WorldEdit to `schematics/<id>.schem`,
  containers emptied first) or leaves (drafts stay).
- **Voting**: residents look at a design in adventure mode and vote 1-5 with colored concrete. Approved = average
  >= town threshold with at least `voting.min-votes` votes. Designers can't vote on their own.
- **Building**: residents claim one house plot; the Alderman builds on town plots. Approved designs of the plot's
  size are pasted (plot cleared first).
- **Treasurer**: deposit coins/ambrosia into your wallet, withdraw coins, give copper/ambrosia/logs/stone to the town
  treasury (residents only). Territories (RPGExplore) also pay into the treasury.

## Code map

| Class | What it does |
|---|---|
| `RPGTowns` | Entry, `/towns` admin commands, plot outlines |
| `TownManager` | Towns, plots, centers, treasuries, plot sizes (`towns.yml`, `config.yml`); `nearest(location)`, `addToTreasury` |
| `DesignManager` | Designs, their slots in the design world, voting result, WorldEdit save/paste |
| `DesignWorld` | The empty world, entering/leaving with inventory saving, protection, vote/menu items |
| `TownMenu` | Planner and Treasurer NPCs and all their dialog menus |

## Admin setup

```
/towns create <id> <name...>
/towns alderman <town> <player>
/towns npc <town> [planner|treasurer]     (first NPC also sets the town center)
/towns center <town>                      (town middle: used by RPGExplore's danger levels)
/towns plot create <town> <plot> <size>   (corner at your feet, extends +X/+Z; sizes: /towns sizes)
/towns plot show <town>
```

Plot sizes (`config.yml`): houses 6x6x12, 8x8x16, 10x10x20; town buildings 16x16x32, 32x32x48.
Building costs (paid from the treasury) are not implemented yet.

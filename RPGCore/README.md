# RPGCore

Classes, stats, leveling from custom mobs, and class checkpoints for the server.

## Build
Open this folder in IntelliJ IDEA (it detects Gradle) and run the `jar` task,
or with Gradle installed: `gradle jar`. The jar lands in `build/libs/`.
Copy it into `minecraft-server/plugins/` and restart the server.

## Files the plugin creates (minecraft-server/plugins/RPGCore/)
- `classes.yml`     – classes: base stats + per-level growth
- `mobs.yml`        – custom enemies (only these give XP)
- `checkpoints.yml` – class markers (made with /rpg checkpoint create)
- `config.yml`      – max level and XP curve

## Code map
- `Stat`              – stat list and caps
- `RpgClass`          – one class; stat(level) = base + perLevel * (level - 1)
- `ClassManager`      – loads/saves classes.yml
- `PlayerManager`     – class/level/XP stored on the player, applies Health and Speed
- `CombatListener`    – damage pipeline, XP on custom mob kills
- `MobManager`        – spawns and tags custom mobs
- `CheckpointManager` – markers that assign a class once
- `RpgCommand`        – /rpg commands

## Stats menu + resource pack
- `/stats` opens `StatsMenu`: a 6-row menu whose title draws a full panel image from the resource pack.
- `tools/make_pack.py` draws the panel and icons and builds `RPGCore-pack.zip` (`pip install pillow`, then `python tools/make_pack.py`).
  Edit colors/pixel maps there, or replace the PNGs in `resourcepack/` with your own art (panel 176x222, icons 16x16).
- Players need the pack. Without it, set `gui.custom-assets: false` in the plugin config to use vanilla items.

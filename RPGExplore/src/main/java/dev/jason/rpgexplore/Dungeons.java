package dev.jason.rpgexplore;

import dev.jason.rpgcore.MobManager;
import dev.jason.rpgcore.Wallet;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dungeons: a circle (saved in explore.yml) that starts when a player walks in. Waves of custom enemies
 * spawn one after another ("ghoul:5,bone_archer:3" = 5 ghouls and 3 bone archers); when the last wave is
 * cleared, everyone who took part gets the reward, and the dungeon rests for its cooldown. If everyone
 * leaves for reset-after-seconds, it resets.
 */
public class Dungeons implements Listener {
    enum State { IDLE, ACTIVE, COOLDOWN }

    static final class Dungeon {
        final String id, world;
        final double x, y, z, radius;
        final int level;
        final List<String> waves;
        final int rewardCopper, rewardAmbrosia, cooldownMinutes;
        State state = State.IDLE;
        int wave;                       // waves spawned so far
        boolean waiting;                // wave cleared, next one coming
        long nextWaveAt, emptySince, cooldownUntil;
        final Set<UUID> mobs = new HashSet<>();
        final Set<UUID> players = new HashSet<>();

        Dungeon(String id, String world, double x, double y, double z, double radius, int level, List<String> waves,
                int rewardCopper, int rewardAmbrosia, int cooldownMinutes) {
            this.id = id;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.level = level;
            this.waves = waves;
            this.rewardCopper = rewardCopper;
            this.rewardAmbrosia = rewardAmbrosia;
            this.cooldownMinutes = cooldownMinutes;
        }

        Location center() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z);
        }

        boolean contains(Location l) {
            Location c = center();
            return c != null && l.getWorld().equals(c.getWorld()) && Math.abs(l.getY() - y) < 24
                    && Util.flatDistance(l, c) <= radius;
        }
    }

    static final List<String> DEFAULT_WAVES = List.of("ghoul:4", "ghoul:3,bone_archer:3", "crypt_knight:2,ghoul:2");

    private final RPGExplore plugin;
    private final NamespacedKey dungeonKey;
    final Map<String, Dungeon> dungeons = new LinkedHashMap<>();

    Dungeons(RPGExplore plugin) {
        this.plugin = plugin;
        this.dungeonKey = new NamespacedKey(plugin, "dungeon");
    }

    void load() {
        ConfigurationSection all = plugin.data().getConfigurationSection("dungeons");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            dungeons.put(id, new Dungeon(id, s.getString("world"), s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    s.getDouble("radius", 12), s.getInt("level", 5), s.getStringList("waves"), s.getInt("reward-copper", 25),
                    s.getInt("reward-ambrosia", 1), s.getInt("cooldown-minutes", 10)));
        }
    }

    void save() {
        plugin.data().set("dungeons", null);
        for (Dungeon d : dungeons.values()) {
            String b = "dungeons." + d.id + ".";
            plugin.data().set(b + "world", d.world);
            plugin.data().set(b + "x", d.x);
            plugin.data().set(b + "y", d.y);
            plugin.data().set(b + "z", d.z);
            plugin.data().set(b + "radius", d.radius);
            plugin.data().set(b + "level", d.level);
            plugin.data().set(b + "waves", d.waves);
            plugin.data().set(b + "reward-copper", d.rewardCopper);
            plugin.data().set(b + "reward-ambrosia", d.rewardAmbrosia);
            plugin.data().set(b + "cooldown-minutes", d.cooldownMinutes);
        }
        plugin.saveData();
    }

    boolean inside(Location l) {
        for (Dungeon d : dungeons.values()) if (d.contains(l)) return true;
        return false;
    }

    void tick() {
        long now = System.currentTimeMillis();
        long delay = plugin.getConfig().getLong("dungeons.wave-delay-seconds", 5) * 1000;
        long reset = plugin.getConfig().getLong("dungeons.reset-after-seconds", 30) * 1000;
        for (Dungeon d : dungeons.values()) {
            Location c = d.center();
            if (c == null) continue;
            List<Player> inside = new ArrayList<>();
            for (Player p : c.getWorld().getPlayers()) if (d.contains(p.getLocation()) && !p.isDead()) inside.add(p);
            switch (d.state) {
                case COOLDOWN -> {
                    if (now >= d.cooldownUntil) d.state = State.IDLE;
                }
                case IDLE -> {
                    if (!inside.isEmpty()) {
                        d.state = State.ACTIVE;
                        d.wave = 0;
                        d.waiting = true;
                        d.nextWaveAt = now + delay;
                        d.players.clear();
                        tell(inside, "<dark_red>The dungeon stirs... <gray>wave 1 of " + d.waves.size() + " in "
                                + delay / 1000 + " seconds.");
                    }
                }
                case ACTIVE -> {
                    inside.forEach(p -> d.players.add(p.getUniqueId()));
                    d.mobs.removeIf(id -> {
                        Entity e = Bukkit.getEntity(id);
                        return e == null || e.isDead() || !e.isValid();
                    });
                    if (inside.isEmpty()) {
                        if (d.emptySince == 0) d.emptySince = now;
                        if (now - d.emptySince > reset) resetDungeon(d);
                        continue;
                    }
                    d.emptySince = 0;
                    if (!d.mobs.isEmpty()) continue;
                    if (!d.waiting) { // wave just cleared
                        if (d.wave >= d.waves.size()) {
                            finish(d, inside);
                            continue;
                        }
                        d.waiting = true;
                        d.nextWaveAt = now + delay;
                        tell(inside, "<gold>Wave cleared! <gray>Wave " + (d.wave + 1) + " of " + d.waves.size() + " in "
                                + delay / 1000 + " seconds.");
                    } else if (now >= d.nextWaveAt) {
                        spawnWave(d, c);
                        d.wave++;
                        d.waiting = false;
                        tell(inside, "<red>Wave " + d.wave + " of " + d.waves.size() + "!");
                        inside.forEach(p -> p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.7f, 1f));
                    }
                }
            }
        }
    }

    private void spawnWave(Dungeon d, Location c) {
        for (String part : d.waves.get(d.wave).split(",")) {
            String[] kv = part.trim().split(":");
            MobManager.MobDef def = plugin.rpg().mobs().get(kv[0]);
            int count = kv.length > 1 ? Integer.parseInt(kv[1].trim()) : 1;
            if (def == null) {
                plugin.getLogger().warning("Dungeon " + d.id + ": unknown mob " + kv[0]);
                continue;
            }
            for (int i = 0; i < count; i++) {
                Location spot = Util.near(c, d.radius * 0.8, 8);
                if (spot == null) spot = c;
                LivingEntity e = plugin.rpg().mobs().spawn(def, spot, d.level);
                if (e == null) continue;
                e.getPersistentDataContainer().set(dungeonKey, PersistentDataType.STRING, d.id);
                d.mobs.add(e.getUniqueId());
            }
        }
    }

    private void finish(Dungeon d, List<Player> inside) {
        for (UUID id : d.players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.COPPER, d.rewardCopper));
            if (d.rewardAmbrosia > 0) Util.give(p, plugin.rpg().wallet().item(Wallet.Currency.AMBROSIA, d.rewardAmbrosia));
            p.sendMessage(RPGExplore.MM.deserialize("<gold>Dungeon cleared! <gray>You got <#d9884a>" + d.rewardCopper
                    + " copper" + (d.rewardAmbrosia > 0 ? "<gray> and <#f2d36b>" + d.rewardAmbrosia + " ambrosia" : "") + "<gray>."));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
        }
        d.state = State.COOLDOWN;
        d.cooldownUntil = System.currentTimeMillis() + d.cooldownMinutes * 60_000L;
        d.players.clear();
    }

    private void resetDungeon(Dungeon d) {
        for (UUID id : d.mobs) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        d.mobs.clear();
        d.players.clear();
        d.state = State.IDLE;
        d.emptySince = 0;
    }

    private void tell(List<Player> players, String msg) {
        players.forEach(p -> p.sendMessage(RPGExplore.MM.deserialize(msg)));
    }

    void cleanup() {
        dungeons.values().forEach(this::resetDungeon);
    }
}
